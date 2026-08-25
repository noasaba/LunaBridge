package dev.lunabridge.paper;

import dev.lunabridge.core.crypto.SessionKeys;
import dev.lunabridge.core.crypto.SharedPassphrase;
import dev.lunabridge.core.delivery.DeliveryStateMachine;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.protocol.AckCodec;
import dev.lunabridge.core.protocol.BridgeMessageCodec;
import dev.lunabridge.core.protocol.ChannelManifestCodec;
import dev.lunabridge.core.protocol.HandshakeMessages;
import dev.lunabridge.core.protocol.ProtocolException;
import dev.lunabridge.core.protocol.ProtocolType;
import dev.lunabridge.core.protocol.SecureFrameCodec;
import dev.lunabridge.core.protocol.WirePacket;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Paper edge for one coherent session/logical-delivery state machine; local LunaChat never waits on it. */
final class PaperNetworkClient implements PluginMessageListener {
    static final String CHANNEL = "lunabridge:network";
    private static final UUID CONTROL_ID = new UUID(0, 0);
    private static final Duration HANDSHAKE_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECONNECT_DELAY = Duration.ofSeconds(1);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(1);
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(3);

    private final JavaPlugin plugin;
    private final PaperSettings settings;
    private final Clock clock;
    private final DeliveryStateMachine<BridgeMessage> deliveries;
    private final SharedPassphrase secret;
    private final byte[] channelManifest;
    private HandshakeMessages.ClientState clientState;
    private HandshakeMessages.Challenge challenge;
    private SecureFrameCodec outbound;
    private SecureFrameCodec inbound;
    private LunaChatAdapter adapter;

    PaperNetworkClient(JavaPlugin plugin, PaperSettings settings) {
        this(plugin, settings, Clock.systemUTC());
    }

    PaperNetworkClient(JavaPlugin plugin, PaperSettings settings, Clock clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.clock = clock;
        this.deliveries = new DeliveryStateMachine<>(settings.outboxLimit, settings.dedupLimit, 5,
                Duration.ofSeconds(1), Duration.ofSeconds(2), clock);
        this.secret = settings.velocityEnabled ? SharedPassphrase.from(settings.sharedPass) : null;
        this.channelManifest = ChannelManifestCodec.encode(settings.channels.asBridgeKeyToLunaName());
    }

    void attachAdapter(LunaChatAdapter adapter) { this.adapter = adapter; }

    void publish(BridgeMessage message) {
        if (!settings.velocityEnabled || secret == null) return;
        DeliveryStateMachine.EnqueueResult queued = deliveries.enqueue(message.id(), message, message.expiresAt());
        if (queued == DeliveryStateMachine.EnqueueResult.FULL) {
            plugin.getLogger().warning("LunaBridge Paper outbox full; dropped logical message " + message.id());
            return;
        }
        flushOutbound();
    }

    void tick() {
        if (!settings.velocityEnabled || secret == null) return;
        boolean carrierAvailable = carrier() != null;
        if (deliveries.handshakeTimedOut()) closeSessionMaterial();
        if (deliveries.sessionUsable() && deliveries.heartbeatTimedOut(HEARTBEAT_TIMEOUT)) {
            invalidate("heartbeat timed out");
        }
        if (deliveries.shouldStartHandshake(carrierAvailable)) beginHandshake();
        if (deliveries.sessionUsable() && deliveries.heartbeatDue() && carrierAvailable) {
            if (sendSecure(ProtocolType.PING, CONTROL_ID, new byte[0])) {
                deliveries.heartbeatSent(HEARTBEAT_INTERVAL);
            }
        }
        flushOutbound();
    }

    void close() {
        closeSessionMaterial();
        deliveries.close();
        if (secret != null) secret.destroy();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] packetBytes) {
        if (!CHANNEL.equals(channel) || !settings.velocityEnabled || secret == null) return;
        try {
            WirePacket.Packet packet = WirePacket.unwrap(packetBytes);
            switch (packet.type()) {
                case CHALLENGE -> onChallenge(packet.payload());
                case ACCEPT -> onAccepted(packet.payload());
                case CHAT_DOWN, ACK, PONG -> onSecure(packet.type(), packet.payload());
                default -> throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE,
                        "unexpected proxy packet " + packet.type());
            }
        } catch (ProtocolException invalid) {
            if (invalid.code() == ProtocolException.Code.REPLAYED) {
                plugin.getLogger().warning("LunaBridge discarded an authenticated replayed frame; session retained.");
                return;
            }
            invalidate("rejected proxy packet: " + invalid.code());
        }
    }

    private void beginHandshake() {
        if (carrier() == null || secret == null) return;
        destroyHandshakeState();
        byte[] serverKey = secret.keyForServer(settings.serverId);
        try {
            clientState = HandshakeMessages.begin(settings.serverId, serverKey, channelManifest, clock);
        } finally {
            java.util.Arrays.fill(serverKey, (byte) 0);
        }
        deliveries.handshakeStarted(HANDSHAKE_TIMEOUT);
        if (!send(ProtocolType.HELLO, HandshakeMessages.encode(clientState.hello()))) {
            deliveries.invalidate(RECONNECT_DELAY);
            destroyHandshakeState();
        }
    }

    private void onChallenge(byte[] payload) throws ProtocolException {
        if (clientState == null || deliveries.sessionPhase() != DeliveryStateMachine.SessionPhase.HANDSHAKING) {
            throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unsolicited challenge");
        }
        challenge = HandshakeMessages.decodeChallenge(payload);
        HandshakeMessages.Proof proof = HandshakeMessages.prove(clientState, challenge, clock);
        if (!send(ProtocolType.PROOF, HandshakeMessages.encode(proof))) {
            throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "no carrier for proof");
        }
    }

    private void onAccepted(byte[] payload) throws ProtocolException {
        if (clientState == null || challenge == null
                || deliveries.sessionPhase() != DeliveryStateMachine.SessionPhase.HANDSHAKING) {
            throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unsolicited acceptance");
        }
        HandshakeMessages.Accepted accepted = HandshakeMessages.decodeAccepted(payload);
        SessionKeys keys = HandshakeMessages.verifyAccepted(clientState, challenge, accepted, clock);
        Instant sessionExpiry = Instant.ofEpochMilli(accepted.expiresAt());
        closeCodecs();
        try {
            outbound = new SecureFrameCodec(accepted.sessionId(), accepted.epoch(), sessionExpiry, keys, true, clock);
            inbound = new SecureFrameCodec(accepted.sessionId(), accepted.epoch(), sessionExpiry, keys, false, clock);
        } finally {
            keys.destroy();
        }
        deliveries.activate(accepted.sessionId(), sessionExpiry, HEARTBEAT_INTERVAL);
        destroyHandshakeState();
        flushOutbound();
    }

    private void onSecure(ProtocolType outerType, byte[] encrypted) throws ProtocolException {
        if (inbound == null || !deliveries.sessionUsable()) {
            throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "secure packet arrived without active session");
        }
        SecureFrameCodec.Decoded decoded = inbound.decode(encrypted);
        if (decoded.type() != outerType) {
            throw new ProtocolException(ProtocolException.Code.MALFORMED, "outer and encrypted types differ");
        }
        deliveries.peerActivity();
        switch (decoded.type()) {
            case PONG -> {
                if (!decoded.logicalMessageId().equals(CONTROL_ID) || decoded.payload().length != 0) {
                    throw new ProtocolException(ProtocolException.Code.MALFORMED, "invalid heartbeat acknowledgement");
                }
            }
            case ACK -> onAcknowledgement(decoded);
            case CHAT_DOWN -> onInboundChat(decoded);
            default -> throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE, "unsupported secure proxy packet");
        }
    }

    private void onAcknowledgement(SecureFrameCodec.Decoded decoded) throws ProtocolException {
        UUID acknowledged = AckCodec.decode(decoded.payload());
        if (!acknowledged.equals(decoded.logicalMessageId())) {
            throw new ProtocolException(ProtocolException.Code.MALFORMED, "acknowledgement identity mismatch");
        }
        deliveries.acknowledge(acknowledged);
    }

    private void onInboundChat(SecureFrameCodec.Decoded decoded) throws ProtocolException {
        if (adapter == null) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "chat arrived without adapter");
        BridgeMessage message = BridgeMessageCodec.decode(decoded.payload());
        if (!message.id().equals(decoded.logicalMessageId())) {
            throw new ProtocolException(ProtocolException.Code.MALFORMED, "frame and logical message identities differ");
        }
        BridgeMessageCodec.requireCurrent(message, clock);
        DeliveryStateMachine.InboundDecision decision = deliveries.beginInbound(message.id(), message.expiresAt());
        if (decision == DeliveryStateMachine.InboundDecision.DELIVERED) {
            sendAcknowledgement(message.id());
            return;
        }
        if (decision == DeliveryStateMachine.InboundDecision.FULL) {
            plugin.getLogger().warning("LunaBridge inbound idempotency window full; deferred " + message.id());
            return;
        }
        if (decision != DeliveryStateMachine.InboundDecision.NEW) return;
        adapter.inject(message, () -> {
            if (deliveries.completeInbound(message.id())) sendAcknowledgement(message.id());
        }, () -> deliveries.failInbound(message.id()));
    }

    private void sendAcknowledgement(UUID logicalId) {
        if (!sendSecure(ProtocolType.ACK, logicalId, AckCodec.encode(logicalId))) {
            plugin.getLogger().warning("LunaBridge could not send delivery acknowledgement for " + logicalId);
        }
    }

    private void flushOutbound() {
        if (!deliveries.sessionUsable()) return;
        for (DeliveryStateMachine.Attempt<BridgeMessage> attempt : deliveries.dueAttempts(64)) {
            boolean sent = sendSecure(ProtocolType.CHAT_UP, attempt.logicalId(), BridgeMessageCodec.encode(attempt.payload()));
            if (sent) deliveries.recordSent(attempt.logicalId());
            else {
                deliveries.recordTransportUnavailable(attempt.logicalId());
                break;
            }
        }
    }

    private boolean sendSecure(ProtocolType type, UUID logicalId, byte[] payload) {
        if (outbound == null || !deliveries.sessionUsable()) return false;
        try {
            return send(type, outbound.encode(type, logicalId, payload, 10_000));
        } catch (ProtocolException invalid) {
            invalidate("could not encode secure frame: " + invalid.code());
            return false;
        }
    }

    private boolean send(ProtocolType type, byte[] payload) {
        Player currentCarrier = carrier();
        if (currentCarrier == null) return false;
        currentCarrier.sendPluginMessage(plugin, CHANNEL, WirePacket.wrap(type, payload));
        return true;
    }

    private Player carrier() { return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null); }

    private void invalidate(String detail) {
        plugin.getLogger().warning("LunaBridge network session unavailable: " + detail);
        closeSessionMaterial();
        deliveries.invalidate(RECONNECT_DELAY);
    }

    private void closeSessionMaterial() {
        closeCodecs();
        destroyHandshakeState();
    }

    private void closeCodecs() {
        if (outbound != null) outbound.close();
        if (inbound != null) inbound.close();
        outbound = null;
        inbound = null;
    }

    private void destroyHandshakeState() {
        if (clientState != null) clientState.destroy();
        clientState = null;
        challenge = null;
    }
}
