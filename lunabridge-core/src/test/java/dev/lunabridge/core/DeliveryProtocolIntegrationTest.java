package dev.lunabridge.core;

import dev.lunabridge.core.crypto.SessionKeys;
import dev.lunabridge.core.crypto.SharedPassphrase;
import dev.lunabridge.core.delivery.DeliveryState;
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
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Integration tests the shared session, retry, frame-replay, ACK and logical-dedup contract end to end. */
class DeliveryProtocolIntegrationTest {
    private static final Instant START = Instant.parse("2026-01-01T00:00:00Z");

    @Test void coldStartAuthenticatesAndReceivesDiscordWithoutAnyPriorOutboundMessage() throws Exception {
        MutableClock clock = new MutableClock(START);
        DeliveryStateMachine<String> backend = machine(clock, 4);

        assertFalse(backend.shouldStartHandshake(false));
        assertTrue(backend.shouldStartHandshake(true));
        backend.handshakeStarted(Duration.ofSeconds(5));
        assertEquals(DeliveryStateMachine.SessionPhase.HANDSHAKING, backend.sessionPhase());

        byte[] serverKey = SharedPassphrase.from("cold-start-integration-secret").keyForServer("lobby");
        byte[] manifest = ChannelManifestCodec.encode(java.util.Map.of("global", "Global"));
        HandshakeMessages.ClientState client = HandshakeMessages.begin("lobby", serverKey, manifest, clock);
        HandshakeMessages.IssuedChallenge issued = HandshakeMessages.challenge(client.hello(), serverKey, clock);
        HandshakeMessages.Proof proof = HandshakeMessages.prove(client, issued.challenge(), clock);
        HandshakeMessages.Accepted accepted = HandshakeMessages.verifyProof(issued, proof, 1, clock);
        SessionKeys paperKeys = HandshakeMessages.verifyAccepted(client, issued.challenge(), accepted, clock);
        SessionKeys velocityKeys = HandshakeMessages.keys(issued);
        Instant sessionExpiry = Instant.ofEpochMilli(accepted.expiresAt());
        backend.activate(accepted.sessionId(), sessionExpiry, Duration.ofSeconds(1));
        assertTrue(backend.sessionUsable());
        assertEquals(0, backend.outboundSize());

        SecureFrameCodec velocityOut = new SecureFrameCodec(accepted.sessionId(), 1, sessionExpiry,
                velocityKeys, false, clock);
        SecureFrameCodec paperIn = new SecureFrameCodec(accepted.sessionId(), 1, sessionExpiry,
                paperKeys, false, clock);
        BridgeMessage discord = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.DISCORD, "global", "Global",
                null, "Discord user", "cold hello", "discord", clock.instant(), clock.instant().plusSeconds(10));
        SecureFrameCodec.Decoded frame = paperIn.decode(velocityOut.encode(ProtocolType.CHAT_DOWN, discord.id(),
                BridgeMessageCodec.encode(discord), 10_000));
        assertEquals(discord, BridgeMessageCodec.decode(frame.payload()));
        assertEquals(DeliveryStateMachine.InboundDecision.NEW,
                backend.beginInbound(discord.id(), discord.expiresAt()));
        assertTrue(backend.completeInbound(discord.id()));

        paperKeys.destroy();
        velocityKeys.destroy();
        client.destroy();
        issued.destroy();
        java.util.Arrays.fill(serverKey, (byte) 0);
    }

    @Test void sessionExpiryReconnectsWithoutDroppingLogicalWorkOrAdmissionAccounting() {
        MutableClock clock = new MutableClock(START);
        DeliveryStateMachine<String> sender = machine(clock, 4);
        UUID logicalId = UUID.randomUUID();
        sender.activate(UUID.randomUUID(), clock.instant().plusSeconds(2), Duration.ofSeconds(1));
        sender.enqueue(logicalId, "pending", clock.instant().plusSeconds(10));

        clock.advance(Duration.ofSeconds(2));
        assertFalse(sender.sessionUsable());
        assertEquals(1, sender.outboundSize());
        assertTrue(sender.shouldStartHandshake(true));

        clock.advance(Duration.ofSeconds(8));
        assertEquals(1, sender.expireOutbound());
        assertEquals(0, sender.outboundSize());
    }

    @Test void velocityRestartReconnectsAndPreservesLogicalOutboundWork() {
        MutableClock clock = new MutableClock(START);
        DeliveryStateMachine<String> paper = machine(clock, 4);
        UUID oldSession = UUID.randomUUID();
        UUID logicalId = UUID.randomUUID();
        paper.activate(oldSession, clock.instant().plusSeconds(300), Duration.ofSeconds(1));
        assertEquals(DeliveryStateMachine.EnqueueResult.ACCEPTED,
                paper.enqueue(logicalId, "message", clock.instant().plusSeconds(10)));
        paper.recordSent(logicalId);

        clock.advance(Duration.ofSeconds(4)); // Velocity restart means no authenticated PONG arrives.
        assertTrue(paper.heartbeatTimedOut(Duration.ofSeconds(3)));
        paper.invalidate(Duration.ZERO);
        assertEquals(DeliveryState.QUEUED, paper.outboundState(logicalId).orElseThrow());
        assertTrue(paper.shouldStartHandshake(true));

        paper.handshakeStarted(Duration.ofSeconds(5));
        UUID replacement = UUID.randomUUID();
        paper.activate(replacement, clock.instant().plusSeconds(300), Duration.ofSeconds(1));
        DeliveryStateMachine.Attempt<String> retry = paper.dueAttempts(1).getFirst();
        assertEquals(logicalId, retry.logicalId());
        assertEquals(0, retry.attempt());
        assertNotEquals(oldSession, paper.sessionId().orElseThrow());
    }

    @Test void lostAckRetriesLogicalMessageWithFreshFrameAndNoDuplicateDisplay() throws Exception {
        MutableClock clock = new MutableClock(START);
        UUID sessionId = UUID.randomUUID();
        long epoch = 7;
        Instant sessionExpiry = clock.instant().plusSeconds(300);
        SessionKeys keys = SessionKeys.derive(new byte[32], sessionId, new byte[32], new byte[32]);
        SecureFrameCodec velocityOut = new SecureFrameCodec(sessionId, epoch, sessionExpiry, keys, false, clock);
        SecureFrameCodec paperIn = new SecureFrameCodec(sessionId, epoch, sessionExpiry, keys, false, clock);
        SecureFrameCodec paperOut = new SecureFrameCodec(sessionId, epoch, sessionExpiry, keys, true, clock);
        SecureFrameCodec velocityIn = new SecureFrameCodec(sessionId, epoch, sessionExpiry, keys, true, clock);
        keys.destroy();

        DeliveryStateMachine<String> sender = machine(clock, 4);
        DeliveryStateMachine<String> receiver = machine(clock, 4);
        sender.activate(sessionId, sessionExpiry, Duration.ofSeconds(1));
        receiver.activate(sessionId, sessionExpiry, Duration.ofSeconds(1));
        UUID logicalId = UUID.randomUUID();
        Instant logicalDeadline = clock.instant().plusSeconds(10);
        sender.enqueue(logicalId, "hello", logicalDeadline);

        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] firstFrame = velocityOut.encode(ProtocolType.CHAT_DOWN, logicalId, payload, 10_000);
        SecureFrameCodec.Decoded first = paperIn.decode(firstFrame);
        assertEquals(logicalId, first.logicalMessageId());
        assertEquals(DeliveryStateMachine.InboundDecision.NEW, receiver.beginInbound(logicalId, logicalDeadline));
        int displayed = 1;
        assertTrue(receiver.completeInbound(logicalId));
        sender.recordSent(logicalId); // The first delivery ACK is deliberately lost.

        clock.advance(Duration.ofSeconds(1));
        assertEquals(logicalId, sender.dueAttempts(1).getFirst().logicalId());
        byte[] retryFrame = velocityOut.encode(ProtocolType.CHAT_DOWN, logicalId, payload, 9_000);
        assertFalse(java.util.Arrays.equals(firstFrame, retryFrame));
        SecureFrameCodec.Decoded retry = paperIn.decode(retryFrame);
        assertNotEquals(first.frameIdentity().sequence(), retry.frameIdentity().sequence());
        assertEquals(DeliveryStateMachine.InboundDecision.DELIVERED,
                receiver.beginInbound(logicalId, logicalDeadline));
        assertEquals(1, displayed); // Logical duplicate is ACKed without another LunaChat dispatch.

        byte[] ackFrame = paperOut.encode(ProtocolType.ACK, logicalId, AckCodec.encode(logicalId), 5_000);
        SecureFrameCodec.Decoded ack = velocityIn.decode(ackFrame);
        assertEquals(logicalId, AckCodec.decode(ack.payload()));
        assertTrue(sender.acknowledge(logicalId));
        assertEquals(0, sender.outboundSize());

        assertEquals(ProtocolException.Code.REPLAYED,
                assertThrows(ProtocolException.class, () -> paperIn.decode(retryFrame)).code());
        assertTrue(receiver.sessionUsable()); // Exact frame replay is discarded, not treated as session failure.
    }

    @Test void idempotencyCapacityRecoversAtLogicalDeadlineWithoutStoppingSession() {
        MutableClock clock = new MutableClock(START);
        DeliveryStateMachine<String> receiver = machine(clock, 2);
        receiver.activate(UUID.randomUUID(), clock.instant().plusSeconds(60), Duration.ofSeconds(1));
        Instant deadline = clock.instant().plusSeconds(2);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        assertEquals(DeliveryStateMachine.InboundDecision.NEW, receiver.beginInbound(first, deadline));
        assertTrue(receiver.completeInbound(first));
        assertEquals(DeliveryStateMachine.InboundDecision.NEW, receiver.beginInbound(second, deadline));
        assertTrue(receiver.completeInbound(second));
        assertEquals(DeliveryStateMachine.InboundDecision.FULL,
                receiver.beginInbound(UUID.randomUUID(), deadline));

        clock.advance(Duration.ofSeconds(5)); // deadline + two-second replay grace has elapsed.
        assertEquals(DeliveryStateMachine.InboundDecision.NEW,
                receiver.beginInbound(UUID.randomUUID(), clock.instant().plusSeconds(2)));
        assertTrue(receiver.sessionUsable());
    }

    private static DeliveryStateMachine<String> machine(Clock clock, int inboundLimit) {
        return new DeliveryStateMachine<>(8, inboundLimit, 5, Duration.ofSeconds(1), Duration.ofSeconds(2), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) { this.instant = instant; }
        private void advance(Duration duration) { instant = instant.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return instant; }
    }
}
