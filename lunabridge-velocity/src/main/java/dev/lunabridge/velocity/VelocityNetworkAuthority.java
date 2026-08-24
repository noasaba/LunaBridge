package dev.lunabridge.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.lunabridge.core.crypto.SessionKeys;
import dev.lunabridge.core.crypto.SharedPassphrase;
import dev.lunabridge.core.delivery.BoundedDedupCache;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import dev.lunabridge.core.protocol.AckCodec;
import dev.lunabridge.core.protocol.BridgeMessageCodec;
import dev.lunabridge.core.protocol.HandshakeMessages;
import dev.lunabridge.core.protocol.ProtocolException;
import dev.lunabridge.core.protocol.ProtocolType;
import dev.lunabridge.core.protocol.SecureFrameCodec;
import dev.lunabridge.core.protocol.WirePacket;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The only authority for cross-backend routing, delivery tracking, deduplication and Discord ingress. */
final class VelocityNetworkAuthority {
    private static final int MAX_CHALLENGES = 128;
    private final ProxyServer proxy;
    private final Logger logger;
    private final SharedPassphrase secret;
    private final int pendingLimit;
    private final long epoch;
    private final Clock clock = Clock.systemUTC();
    private final BoundedDedupCache received;
    private final Map<UUID, ChallengeRecord> challenges = new LinkedHashMap<>();
    private final Map<String, BackendSession> sessions = new LinkedHashMap<>();
    private int pendingInFlight;
    private DiscordGateway discord = DiscordGateway.disabled();

    VelocityNetworkAuthority(ProxyServer proxy, Logger logger, VelocitySettings settings, long epoch) {
        this.proxy = proxy; this.logger = logger; this.secret = SharedPassphrase.from(settings.sharedPass);
        this.pendingLimit = settings.pendingDeliveries; this.epoch = epoch;
        this.received = new BoundedDedupCache(settings.dedupEntries, java.time.Duration.ofHours(24), clock);
    }

    synchronized void attachDiscord(DiscordGateway gateway) { this.discord = gateway; }

    synchronized void receive(ServerConnection connection, byte[] packetBytes) {
        String serverId = connection.getServerInfo().getName();
        try {
            WirePacket.Packet packet = WirePacket.unwrap(packetBytes);
            switch (packet.type()) {
                case HELLO -> receiveHello(connection, serverId, packet.payload());
                case PROOF -> receiveProof(connection, serverId, packet.payload());
                case CHAT_UP, ACK -> receiveSecure(connection, serverId, packet.type(), packet.payload());
                default -> reject(serverId, "unexpected packet " + packet.type());
            }
        } catch (ProtocolException | IllegalArgumentException rejected) {
            reject(serverId, rejected.getMessage());
        }
    }

    synchronized void routeDiscordInbound(BridgeMessage message) {
        if (message.origin() != BridgeOrigin.DISCORD) throw new IllegalArgumentException("only Discord ingress is accepted here");
        if (received.admit(message.id()) != BoundedDedupCache.Result.NEW) return;
        route(message, null);
    }

    synchronized List<String> onlinePlayerNames() {
        return proxy.getAllPlayers().stream().map(player -> player.getUsername()).sorted().toList();
    }

    synchronized void tick() {
        Instant now = clock.instant();
        for (BackendSession session : sessions.values()) session.retryDue(now);
        challenges.entrySet().removeIf(entry -> entry.getValue().challenge.challenge().expiresAt() < clock.millis());
    }

    synchronized void close() {
        sessions.values().forEach(BackendSession::clearPending);
        sessions.clear(); challenges.clear(); secret.destroy(); discord = DiscordGateway.disabled();
    }

    private void receiveHello(ServerConnection connection, String serverId, byte[] payload) throws ProtocolException {
        HandshakeMessages.Hello hello = HandshakeMessages.decodeHello(payload);
        if (!serverId.equals(hello.serverId())) throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "backend identity mismatch");
        HandshakeMessages.IssuedChallenge challenge = HandshakeMessages.challenge(hello, secret.keyForServer(serverId), clock);
        // Only an authenticated hello may replace the backend's previous pending challenge.
        challenges.entrySet().removeIf(entry -> entry.getValue().serverId.equals(serverId));
        if (challenges.size() >= MAX_CHALLENGES) throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "handshake admission full");
        challenges.put(challenge.challenge().sessionId(), new ChallengeRecord(serverId, challenge));
        send(connection, ProtocolType.CHALLENGE, HandshakeMessages.encode(challenge.challenge()));
    }

    private void receiveProof(ServerConnection connection, String serverId, byte[] payload) throws ProtocolException {
        HandshakeMessages.Proof proof = HandshakeMessages.decodeProof(payload);
        ChallengeRecord record = challenges.remove(proof.sessionId());
        if (record == null || !record.serverId.equals(serverId)) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unknown handshake proof");
        HandshakeMessages.Accepted accepted = HandshakeMessages.verifyProof(record.challenge, proof, epoch, clock);
        if (!sessions.containsKey(serverId) && sessions.size() >= 512) {
            throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "backend session ceiling reached");
        }
        SessionKeys keys = HandshakeMessages.keys(record.challenge);
        BackendSession replacement = new BackendSession(serverId,
                new SecureFrameCodec(accepted.sessionId(), epoch, keys, true, clock),
                new SecureFrameCodec(accepted.sessionId(), epoch, keys, false, clock), pendingLimit);
        BackendSession prior = sessions.put(serverId, replacement);
        if (prior != null) prior.clearPending();
        keys.destroy();
        send(connection, ProtocolType.ACCEPT, HandshakeMessages.encode(accepted));
    }

    private void receiveSecure(ServerConnection connection, String serverId, ProtocolType outerType, byte[] payload) throws ProtocolException {
        BackendSession session = sessions.get(serverId);
        if (session == null) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "unregistered backend session");
        SecureFrameCodec.Decoded frame = session.inbound.decode(payload);
        if (frame.type() != outerType) throw new ProtocolException(ProtocolException.Code.MALFORMED, "outer and encrypted types differ");
        if (frame.type() == ProtocolType.ACK) {
            if (session.pending.remove(AckCodec.decode(frame.payload())) != null) releasePending();
            return;
        }
        if (frame.type() != ProtocolType.CHAT_UP) throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE, "unsupported inbound frame");
        BridgeMessage message = BridgeMessageCodec.decode(frame.payload());
        if (message.origin() != BridgeOrigin.MINECRAFT || !serverId.equals(message.sourceServer()) || message.expiresAt().isBefore(clock.instant())) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "invalid message authority");
        }
        BoundedDedupCache.Result dedup = received.admit(message.id());
        if (dedup == BoundedDedupCache.Result.FULL) throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "dedup full");
        sendAcknowledgement(session, connection, message.id());
        if (dedup == BoundedDedupCache.Result.NEW) route(message, serverId);
    }

    private void route(BridgeMessage message, String originServer) {
        if (message.origin() == BridgeOrigin.MINECRAFT) discord.relayMinecraft(message);
        for (BackendSession destination : sessions.values()) {
            if (!destination.serverId.equals(originServer)) destination.deliver(message);
        }
    }

    private void sendAcknowledgement(BackendSession source, ServerConnection carrier, UUID messageId) {
        try { send(carrier, ProtocolType.ACK, source.outbound.encode(ProtocolType.ACK, messageId, AckCodec.encode(messageId), 10_000)); }
        catch (ProtocolException rejected) { reject(source.serverId, "cannot emit acknowledgement"); }
    }
    private void send(ServerConnection connection, ProtocolType type, byte[] payload) {
        connection.sendPluginMessage(LunaBridgeVelocityPlugin.CHANNEL, WirePacket.wrap(type, payload));
    }
    private void reject(String backend, String detail) { logger.warn("LunaBridge rejected network data from {}: {}", backend, detail); }
    private ServerConnection carrierFor(String serverId) {
        return proxy.getServer(serverId)
                .flatMap(server -> server.getPlayersConnected().stream()
                        .flatMap(player -> player.getCurrentServer().stream())
                        .filter(connection -> serverId.equals(connection.getServerInfo().getName()))
                        .findFirst())
                .orElse(null);
    }
    private boolean reservePending() { if (pendingInFlight >= pendingLimit) return false; pendingInFlight++; return true; }
    private void releasePending() { if (pendingInFlight > 0) pendingInFlight--; }

    private record ChallengeRecord(String serverId, HandshakeMessages.IssuedChallenge challenge) { }

    private final class BackendSession {
        private final String serverId;
        private final SecureFrameCodec inbound;
        private final SecureFrameCodec outbound;
        private final int maxPending;
        private final Map<UUID, PendingFrame> pending = new LinkedHashMap<>();

        BackendSession(String serverId, SecureFrameCodec inbound, SecureFrameCodec outbound, int maxPending) {
            this.serverId = serverId; this.inbound = inbound; this.outbound = outbound; this.maxPending = maxPending;
        }
        void deliver(BridgeMessage message) {
            ServerConnection carrier = carrierFor(serverId);
            if (carrier == null) return;
            if (pending.size() >= maxPending || !reservePending()) {
                logger.warn("LunaBridge global delivery queue full; dropping {} for {}", message.id(), serverId);
                return;
            }
            try {
                byte[] encrypted = outbound.encode(ProtocolType.CHAT_DOWN, message.id(), BridgeMessageCodec.encode(message), 10_000);
                byte[] packet = WirePacket.wrap(ProtocolType.CHAT_DOWN, encrypted);
                if (!carrier.sendPluginMessage(LunaBridgeVelocityPlugin.CHANNEL, packet)) {
                    releasePending();
                    logger.warn("LunaBridge has no active plugin-message carrier for {}", serverId);
                    return;
                }
                pending.put(message.id(), new PendingFrame(packet, 0, clock.instant().plusSeconds(1), message.expiresAt()));
            } catch (ProtocolException rejected) {
                releasePending();
                logger.warn("LunaBridge could not encode delivery to {}", serverId);
            }
        }
        void retryDue(Instant now) {
            Iterator<Map.Entry<UUID, PendingFrame>> iterator = pending.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, PendingFrame> entry = iterator.next(); PendingFrame frame = entry.getValue();
                if (!frame.deadline.isAfter(now) || frame.attempt >= 2) { iterator.remove(); releasePending(); continue; }
                if (!frame.dueAt.isAfter(now)) {
                    ServerConnection carrier = carrierFor(serverId);
                    if (carrier == null || !carrier.sendPluginMessage(LunaBridgeVelocityPlugin.CHANNEL, frame.bytes)) continue;
                    entry.setValue(new PendingFrame(frame.bytes, frame.attempt + 1, now.plusSeconds(1L << frame.attempt), frame.deadline));
                }
            }
        }
        void clearPending() {
            int removed = pending.size();
            pending.clear();
            for (int index = 0; index < removed; index++) releasePending();
        }
    }
    private record PendingFrame(byte[] bytes, int attempt, Instant dueAt, Instant deadline) { }
}
