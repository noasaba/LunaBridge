package dev.lunabridge.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.lunabridge.core.crypto.SessionKeys;
import dev.lunabridge.core.crypto.SharedPassphrase;
import dev.lunabridge.core.delivery.DeliveryStateMachine;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import dev.lunabridge.core.protocol.AckCodec;
import dev.lunabridge.core.protocol.BridgeMessageCodec;
import dev.lunabridge.core.protocol.ChannelManifestCodec;
import dev.lunabridge.core.protocol.HandshakeMessages;
import dev.lunabridge.core.protocol.ProtocolException;
import dev.lunabridge.core.protocol.ProtocolType;
import dev.lunabridge.core.protocol.SecureFrameCodec;
import dev.lunabridge.core.protocol.WirePacket;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/** Velocity's sole routing authority, backed by the same logical-delivery state machine as each Paper edge. */
final class VelocityNetworkAuthority {
    private static final int MAX_CHALLENGES = 128;
    private static final int MAX_SESSIONS = 512;
    private static final UUID CONTROL_ID = new UUID(0, 0);
    private static final Duration RETRY_BASE = Duration.ofSeconds(1);
    private static final Duration IDEMPOTENCY_GRACE = Duration.ofSeconds(2);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(1);

    private final ProxyServer proxy;
    private final Logger logger;
    private final SharedPassphrase secret;
    private final int pendingLimit;
    private final int dedupEntries;
    private final long epoch;
    private final Clock clock;
    private final Map<UUID, ChallengeRecord> challenges = new LinkedHashMap<>();
    private final Map<String, BackendSession> sessions = new LinkedHashMap<>();
    private int pendingInFlight;
    private DiscordGateway discord = DiscordGateway.disabled();

    VelocityNetworkAuthority(ProxyServer proxy, Logger logger, VelocitySettings settings, long epoch) {
        this(proxy, logger, settings, epoch, Clock.systemUTC());
    }

    VelocityNetworkAuthority(ProxyServer proxy, Logger logger, VelocitySettings settings, long epoch, Clock clock) {
        this.proxy = proxy;
        this.logger = logger;
        this.secret = SharedPassphrase.from(settings.sharedPass);
        this.pendingLimit = settings.pendingDeliveries;
        this.dedupEntries = settings.dedupEntries;
        this.epoch = epoch;
        this.clock = clock;
    }

    synchronized void attachDiscord(DiscordGateway gateway) { this.discord = gateway; }

    synchronized void receive(ServerConnection connection, byte[] packetBytes) {
        String serverId = connection.getServerInfo().getName();
        try {
            WirePacket.Packet packet = WirePacket.unwrap(packetBytes);
            switch (packet.type()) {
                case HELLO -> receiveHello(connection, serverId, packet.payload());
                case PROOF -> receiveProof(connection, serverId, packet.payload());
                case CHAT_UP, ACK, PING -> receiveSecure(connection, serverId, packet.type(), packet.payload());
                default -> throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE,
                        "unexpected backend packet " + packet.type());
            }
        } catch (ProtocolException rejected) {
            reject(serverId, null, rejected.code(), rejected.getMessage());
        } catch (IllegalArgumentException rejected) {
            reject(serverId, null, ProtocolException.Code.MALFORMED, rejected.getMessage());
        }
    }

    synchronized void routeDiscordInbound(String bridgeChannel, String authorName, String content) {
        String lunaChannel = canonicalLunaChannel(bridgeChannel);
        if (lunaChannel == null) {
            logger.warn("LunaBridge Discord ingress has no authenticated backend capability for bridge {}", bridgeChannel);
            return;
        }
        Instant now = clock.instant();
        BridgeMessage message = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.DISCORD, bridgeChannel,
                lunaChannel, null, authorName, content, "discord", now, now.plusSeconds(10));
        route(message, null);
    }

    synchronized List<String> onlinePlayerNames() {
        return proxy.getAllPlayers().stream().map(player -> player.getUsername()).sorted().toList();
    }

    synchronized void tick() {
        Instant now = clock.instant();
        for (BackendSession session : sessions.values()) session.tick();
        List<UUID> expired = new ArrayList<>();
        challenges.forEach((id, record) -> {
            if (record.challenge().challenge().expiresAt() <= now.toEpochMilli()) expired.add(id);
        });
        expired.forEach(id -> {
            ChallengeRecord removed = challenges.remove(id);
            if (removed != null) removed.challenge().destroy();
        });
    }

    synchronized void close() {
        sessions.values().forEach(BackendSession::close);
        sessions.clear();
        challenges.values().forEach(record -> record.challenge().destroy());
        challenges.clear();
        secret.destroy();
        discord = DiscordGateway.disabled();
    }

    private void receiveHello(ServerConnection connection, String serverId, byte[] payload) throws ProtocolException {
        HandshakeMessages.Hello hello = HandshakeMessages.decodeHello(payload);
        if (!serverId.equals(hello.serverId())) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "backend identity mismatch");
        }
        byte[] serverKey = secret.keyForServer(serverId);
        HandshakeMessages.IssuedChallenge issued;
        try { issued = HandshakeMessages.challenge(hello, serverKey, clock); }
        finally { java.util.Arrays.fill(serverKey, (byte) 0); }
        Map<String, String> manifest;
        try {
            manifest = ChannelManifestCodec.decode(hello.capabilities());
            validateManifest(serverId, manifest);
        } catch (ProtocolException invalid) {
            issued.destroy();
            throw invalid;
        }
        challenges.entrySet().removeIf(entry -> {
            if (!entry.getValue().serverId().equals(serverId)) return false;
            entry.getValue().challenge().destroy();
            return true;
        });
        if (challenges.size() >= MAX_CHALLENGES) {
            issued.destroy();
            throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "handshake admission full");
        }
        challenges.put(issued.challenge().sessionId(), new ChallengeRecord(serverId, issued, manifest));
        if (!send(connection, ProtocolType.CHALLENGE, HandshakeMessages.encode(issued.challenge()))) {
            ChallengeRecord removed = challenges.remove(issued.challenge().sessionId());
            if (removed != null) removed.challenge().destroy();
        }
    }

    private void receiveProof(ServerConnection connection, String serverId, byte[] payload) throws ProtocolException {
        HandshakeMessages.Proof proof = HandshakeMessages.decodeProof(payload);
        ChallengeRecord record = challenges.remove(proof.sessionId());
        if (record == null || !record.serverId().equals(serverId)) {
            if (record != null) record.challenge().destroy();
            throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unknown handshake proof");
        }
        try {
            validateManifest(serverId, record.manifest());
            HandshakeMessages.Accepted accepted = HandshakeMessages.verifyProof(record.challenge(), proof, epoch, clock);
            BackendSession session = sessions.get(serverId);
            if (session == null) {
                if (sessions.size() >= MAX_SESSIONS) {
                    throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "backend session ceiling reached");
                }
                session = new BackendSession(serverId);
                sessions.put(serverId, session);
            }
            SessionKeys keys = HandshakeMessages.keys(record.challenge());
            try { session.install(accepted, keys, record.manifest()); }
            finally { keys.destroy(); }
            send(connection, ProtocolType.ACCEPT, HandshakeMessages.encode(accepted));
        } finally {
            record.challenge().destroy();
        }
    }

    private void receiveSecure(ServerConnection connection, String serverId, ProtocolType outerType, byte[] payload) {
        BackendSession session = sessions.get(serverId);
        if (session == null) {
            reject(serverId, null, ProtocolException.Code.INVALID_SESSION, "unregistered backend session");
            return;
        }
        try {
            session.receive(connection, outerType, payload);
        } catch (ProtocolException rejected) {
            if (rejected.code() == ProtocolException.Code.REPLAYED) {
                reject(serverId, null, rejected.code(), "authenticated exact-frame replay discarded; session retained");
                return;
            }
            session.invalidate();
            reject(serverId, null, rejected.code(), rejected.getMessage());
        }
    }

    private void route(BridgeMessage message, String originServer) {
        if (relaysToDiscord(message)) discord.relayMinecraft(message);
        for (BackendSession destination : sessions.values()) {
            if (!destination.serverId.equals(originServer)) destination.deliver(message);
        }
    }

    private String canonicalLunaChannel(String bridgeChannel) {
        String canonical = null;
        for (BackendSession session : sessions.values()) {
            String candidate = session.manifest.get(bridgeChannel);
            if (candidate == null) continue;
            if (canonical != null && !canonical.equals(candidate)) return null;
            canonical = candidate;
        }
        return canonical;
    }

    private void validateManifest(String registeringServer, Map<String, String> candidate) throws ProtocolException {
        for (BackendSession session : sessions.values()) {
            if (session.serverId.equals(registeringServer)) continue;
            ChannelManifestCodec.requireCompatible(session.manifest, candidate);
        }
    }

    private boolean send(ServerConnection connection, ProtocolType type, byte[] payload) {
        return connection.sendPluginMessage(LunaBridgeVelocityPlugin.CHANNEL, WirePacket.wrap(type, payload));
    }

    private void reject(String backend, UUID logicalId, ProtocolException.Code code, String detail) {
        logger.warn("LunaBridge rejected backend={} logicalId={} code={} detail={}",
                backend, logicalId == null ? "unknown" : logicalId, code, detail == null ? "" : detail);
    }

    private ServerConnection carrierFor(String serverId) {
        return proxy.getServer(serverId)
                .map(server -> selectCurrentCarrier(server.getPlayersConnected().stream()
                        .flatMap(player -> player.getCurrentServer().stream())
                        .toList(), connection -> serverId.equals(connection.getServerInfo().getName())))
                .orElse(null);
    }

    static boolean relaysToDiscord(BridgeMessage message) {
        return message.origin() == BridgeOrigin.MINECRAFT;
    }

    static <T> T selectCurrentCarrier(Iterable<T> candidates, Predicate<T> currentBackend) {
        for (T candidate : candidates) if (currentBackend.test(candidate)) return candidate;
        return null;
    }

    private boolean reservePending() {
        if (pendingInFlight >= pendingLimit) return false;
        pendingInFlight++;
        return true;
    }

    private void releasePending(int count) { pendingInFlight = Math.max(0, pendingInFlight - count); }

    private record ChallengeRecord(String serverId, HandshakeMessages.IssuedChallenge challenge,
                                   Map<String, String> manifest) {
        private ChallengeRecord { manifest = Map.copyOf(manifest); }
    }

    private final class BackendSession {
        private final String serverId;
        private final DeliveryStateMachine<BridgeMessage> deliveries;
        private Map<String, String> manifest = Map.of();
        private SecureFrameCodec inbound;
        private SecureFrameCodec outbound;

        private BackendSession(String serverId) {
            this.serverId = serverId;
            this.deliveries = new DeliveryStateMachine<>(pendingLimit, dedupEntries, 5,
                    RETRY_BASE, IDEMPOTENCY_GRACE, clock);
        }

        private void install(HandshakeMessages.Accepted accepted, SessionKeys keys, Map<String, String> newManifest) {
            closeCodecs();
            Instant expiry = Instant.ofEpochMilli(accepted.expiresAt());
            inbound = new SecureFrameCodec(accepted.sessionId(), epoch, expiry, keys, true, clock);
            outbound = new SecureFrameCodec(accepted.sessionId(), epoch, expiry, keys, false, clock);
            manifest = Map.copyOf(newManifest);
            deliveries.activate(accepted.sessionId(), expiry, HEARTBEAT_INTERVAL);
        }

        private void receive(ServerConnection carrier, ProtocolType outerType, byte[] payload) throws ProtocolException {
            if (inbound == null || !deliveries.sessionUsable()) {
                throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "inactive backend session");
            }
            SecureFrameCodec.Decoded frame = inbound.decode(payload);
            if (frame.type() != outerType) {
                throw new ProtocolException(ProtocolException.Code.MALFORMED, "outer and encrypted types differ");
            }
            deliveries.peerActivity();
            switch (frame.type()) {
                case PING -> receivePing(carrier, frame);
                case ACK -> receiveAcknowledgement(frame);
                case CHAT_UP -> receiveChat(carrier, frame);
                default -> throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE, "unsupported backend secure frame");
            }
        }

        private void receivePing(ServerConnection carrier, SecureFrameCodec.Decoded frame) throws ProtocolException {
            if (!frame.logicalMessageId().equals(CONTROL_ID) || frame.payload().length != 0) {
                throw new ProtocolException(ProtocolException.Code.MALFORMED, "invalid heartbeat");
            }
            sendSecure(carrier, ProtocolType.PONG, CONTROL_ID, new byte[0]);
        }

        private void receiveAcknowledgement(SecureFrameCodec.Decoded frame) throws ProtocolException {
            UUID acknowledged = AckCodec.decode(frame.payload());
            if (!acknowledged.equals(frame.logicalMessageId())) {
                throw new ProtocolException(ProtocolException.Code.MALFORMED, "acknowledgement identity mismatch");
            }
            releaseExpired();
            if (deliveries.acknowledge(acknowledged)) releasePending(1);
        }

        private void receiveChat(ServerConnection carrier, SecureFrameCodec.Decoded frame) throws ProtocolException {
            BridgeMessage message = BridgeMessageCodec.decode(frame.payload());
            if (!message.id().equals(frame.logicalMessageId())) {
                throw new ProtocolException(ProtocolException.Code.MALFORMED, "frame and logical message identities differ");
            }
            BridgeMessageCodec.requireCurrent(message, clock);
            String mappedChannel = manifest.get(message.bridgeChannel());
            if (message.origin() != BridgeOrigin.MINECRAFT || !serverId.equals(message.sourceServer())
                    || mappedChannel == null || !mappedChannel.equals(message.lunaChannelName())) {
                throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "invalid message authority or channel capability");
            }
            DeliveryStateMachine.InboundDecision decision = deliveries.beginInbound(message.id(), message.expiresAt());
            if (decision == DeliveryStateMachine.InboundDecision.FULL) {
                reject(serverId, message.id(), ProtocolException.Code.LIMIT_EXCEEDED,
                        "logical idempotency window temporarily full");
                return;
            }
            if (decision == DeliveryStateMachine.InboundDecision.DELIVERED) {
                sendAcknowledgement(carrier, message.id());
                return;
            }
            if (decision != DeliveryStateMachine.InboundDecision.NEW) return;
            try {
                route(message, serverId);
                deliveries.completeInbound(message.id());
                sendAcknowledgement(carrier, message.id());
            } catch (RuntimeException routingFailure) {
                deliveries.failInbound(message.id());
                throw routingFailure;
            }
        }

        private void sendAcknowledgement(ServerConnection carrier, UUID logicalId) throws ProtocolException {
            sendSecure(carrier, ProtocolType.ACK, logicalId, AckCodec.encode(logicalId));
        }

        private void deliver(BridgeMessage message) {
            String mappedChannel = manifest.get(message.bridgeChannel());
            if (mappedChannel == null || !mappedChannel.equals(message.lunaChannelName())) return;
            releaseExpired();
            if (!reservePending()) {
                logger.warn("LunaBridge global delivery queue full; dropped logicalId={} backend={}", message.id(), serverId);
                return;
            }
            DeliveryStateMachine.EnqueueResult queued = deliveries.enqueue(message.id(), message, message.expiresAt());
            if (queued != DeliveryStateMachine.EnqueueResult.ACCEPTED) {
                releasePending(1);
                return;
            }
            flush();
        }

        private void tick() {
            releaseExpired();
            if (!deliveries.sessionUsable()) closeCodecs();
            flush();
        }

        private void flush() {
            if (outbound == null || !deliveries.sessionUsable()) return;
            for (DeliveryStateMachine.Attempt<BridgeMessage> attempt : deliveries.dueAttempts(64)) {
                ServerConnection carrier = carrierFor(serverId);
                if (carrier == null) {
                    deliveries.recordTransportUnavailable(attempt.logicalId());
                    break;
                }
                try {
                    byte[] payload = BridgeMessageCodec.encode(attempt.payload());
                    byte[] encrypted = outbound.encode(ProtocolType.CHAT_DOWN, attempt.logicalId(), payload, 10_000);
                    if (send(carrier, ProtocolType.CHAT_DOWN, encrypted)) deliveries.recordSent(attempt.logicalId());
                    else deliveries.recordTransportUnavailable(attempt.logicalId());
                } catch (ProtocolException invalid) {
                    invalidate();
                    reject(serverId, attempt.logicalId(), invalid.code(), "could not encode destination frame");
                    break;
                }
            }
        }

        private void sendSecure(ServerConnection carrier, ProtocolType type, UUID logicalId, byte[] payload)
                throws ProtocolException {
            if (outbound == null || !deliveries.sessionUsable()) {
                throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "inactive outbound session");
            }
            byte[] encrypted = outbound.encode(type, logicalId, payload, 10_000);
            send(carrier, type, encrypted);
        }

        private void invalidate() {
            closeCodecs();
            deliveries.invalidate(Duration.ZERO);
        }

        private void releaseExpired() { releasePending(deliveries.expireOutbound()); }

        private void close() {
            closeCodecs();
            releasePending(deliveries.clearOutbound());
            deliveries.close();
        }

        private void closeCodecs() {
            if (inbound != null) inbound.close();
            if (outbound != null) outbound.close();
            inbound = null;
            outbound = null;
        }
    }
}
