package dev.lunabridge.paper;

import dev.lunabridge.core.crypto.SessionKeys;
import dev.lunabridge.core.crypto.SharedPassphrase;
import dev.lunabridge.core.delivery.BoundedRetryQueue;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.protocol.AckCodec;
import dev.lunabridge.core.protocol.BridgeMessageCodec;
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

/** Paper's best-effort edge. Failure here only drops bounded bridge work; it never affects LunaChat. */
final class PaperNetworkClient implements PluginMessageListener {
    static final String CHANNEL = "lunabridge:network";
    private final JavaPlugin plugin;
    private final PaperSettings settings;
    private final Clock clock = Clock.systemUTC();
    private final BoundedRetryQueue<BridgeMessage> pending;
    private final SharedPassphrase secret;
    private HandshakeMessages.ClientState clientState;
    private HandshakeMessages.Challenge challenge;
    private SecureFrameCodec outbound;
    private SecureFrameCodec inbound;
    private boolean handshakeInFlight;
    private Instant handshakeDeadline;
    private LunaChatAdapter adapter;

    PaperNetworkClient(JavaPlugin plugin, PaperSettings settings) {
        this.plugin = plugin;
        this.settings = settings;
        this.pending = new BoundedRetryQueue<>(settings.outboxLimit, 3, clock);
        this.secret = settings.velocityEnabled ? SharedPassphrase.from(settings.sharedPass) : null;
    }

    void attachAdapter(LunaChatAdapter adapter) { this.adapter = adapter; }
    void publish(BridgeMessage message) {
        if (!settings.velocityEnabled || secret == null) return;
        if (outbound == null) {
            pending.offer(message.id(), message, message.expiresAt());
            beginHandshake();
            return;
        }
        if (!sendSecure(ProtocolType.CHAT_UP, message.id(), BridgeMessageCodec.encode(message))) {
            pending.offer(message.id(), message, message.expiresAt());
        }
    }

    void tick() {
        if (!settings.velocityEnabled) return;
        if (outbound != null) { flushPending(); return; }
        if (handshakeInFlight && handshakeDeadline != null && !handshakeDeadline.isAfter(clock.instant())) {
            invalidate("handshake timed out");
        }
        if (pending.size() > 0) beginHandshake();
    }

    void close() {
        if (secret != null) secret.destroy();
        outbound = null;
        inbound = null;
        clientState = null;
        challenge = null;
        handshakeDeadline = null;
        handshakeInFlight = false;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] packetBytes) {
        if (!CHANNEL.equals(channel) || !settings.velocityEnabled || secret == null) return;
        try {
            WirePacket.Packet packet = WirePacket.unwrap(packetBytes);
            switch (packet.type()) {
                case CHALLENGE -> onChallenge(packet.payload());
                case ACCEPT -> onAccepted(packet.payload());
                case CHAT_DOWN -> onInboundChat(packet.payload());
                case ACK -> { /* source acknowledgements are informational at this best-effort edge */ }
                default -> plugin.getLogger().warning("Rejected unexpected proxy packet type " + packet.type());
            }
        } catch (ProtocolException invalid) {
            // Authentication/protocol failures are hard network failures: clear the session and never downgrade transport.
            invalidate("rejected proxy packet: " + invalid.code());
        }
    }

    private void beginHandshake() {
        if (handshakeInFlight || secret == null || carrier() == null) return;
        clientState = HandshakeMessages.begin(settings.serverId, secret.keyForServer(settings.serverId), clock);
        handshakeInFlight = true;
        handshakeDeadline = clock.instant().plusSeconds(5);
        if (!send(ProtocolType.HELLO, HandshakeMessages.encode(clientState.hello()))) {
            handshakeInFlight = false;
            handshakeDeadline = null;
        }
    }

    private void onChallenge(byte[] payload) throws ProtocolException {
        if (clientState == null || !handshakeInFlight) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unsolicited challenge");
        challenge = HandshakeMessages.decodeChallenge(payload);
        HandshakeMessages.Proof proof = HandshakeMessages.prove(clientState, challenge, clock);
        handshakeDeadline = clock.instant().plusSeconds(5);
        if (!send(ProtocolType.PROOF, HandshakeMessages.encode(proof))) invalidate("no carrier for proof");
    }

    private void onAccepted(byte[] payload) throws ProtocolException {
        if (clientState == null || challenge == null || !handshakeInFlight) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unsolicited acceptance");
        HandshakeMessages.Accepted accepted = HandshakeMessages.decodeAccepted(payload);
        SessionKeys keys = HandshakeMessages.verifyAccepted(clientState, challenge, accepted, clock);
        outbound = new SecureFrameCodec(accepted.sessionId(), accepted.epoch(), keys, true, clock);
        inbound = new SecureFrameCodec(accepted.sessionId(), accepted.epoch(), keys, false, clock);
        keys.destroy();
        handshakeInFlight = false;
        handshakeDeadline = null;
        flushPending();
    }

    private void onInboundChat(byte[] encrypted) throws ProtocolException {
        if (inbound == null || adapter == null) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "chat arrived without session");
        SecureFrameCodec.Decoded decoded = inbound.decode(encrypted);
        if (decoded.type() != ProtocolType.CHAT_DOWN) throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE, "expected chat down");
        BridgeMessage message = BridgeMessageCodec.decode(decoded.payload());
        if (message.expiresAt().isBefore(Instant.now())) throw new ProtocolException(ProtocolException.Code.EXPIRED, "bridge message expired");
        adapter.inject(message, () -> sendSecure(ProtocolType.ACK, message.id(), AckCodec.encode(message.id())));
    }

    private boolean sendSecure(ProtocolType outerType, UUID requestId, byte[] payload) {
        if (outbound == null) return false;
        try {
            return send(outerType, outbound.encode(outerType, requestId, payload, 10_000));
        } catch (ProtocolException invalid) {
            invalidate("could not encode secure frame: " + invalid.code());
            return false;
        }
    }

    private boolean send(ProtocolType type, byte[] payload) {
        Player carrier = carrier();
        if (carrier == null) return false;
        carrier.sendPluginMessage(plugin, CHANNEL, WirePacket.wrap(type, payload));
        return true;
    }

    private Player carrier() { return Bukkit.getOnlinePlayers().stream().findFirst().orElse(null); }
    private void flushPending() {
        for (BoundedRetryQueue.Item<BridgeMessage> item : pending.takeDue()) {
            if (outbound != null && sendSecure(ProtocolType.CHAT_UP, item.id(), BridgeMessageCodec.encode(item.value()))) continue;
            long delaySeconds = 1L << Math.min(item.attempt(), 4);
            pending.retry(item, Duration.ofSeconds(delaySeconds));
        }
    }
    private void invalidate(String detail) {
        plugin.getLogger().warning("LunaBridge network session unavailable: " + detail);
        outbound = null; inbound = null; challenge = null; clientState = null; handshakeInFlight = false; handshakeDeadline = null;
    }
}
