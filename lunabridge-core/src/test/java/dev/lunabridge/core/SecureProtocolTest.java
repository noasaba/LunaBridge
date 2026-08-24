package dev.lunabridge.core;

import dev.lunabridge.core.crypto.SessionKeys;
import dev.lunabridge.core.crypto.SharedPassphrase;
import dev.lunabridge.core.protocol.HandshakeMessages;
import dev.lunabridge.core.protocol.ProtocolException;
import dev.lunabridge.core.protocol.ProtocolType;
import dev.lunabridge.core.protocol.SecureFrameCodec;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SecureProtocolTest {
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_700_000_000_000L), ZoneOffset.UTC);

    @Test void handshakeDerivesMatchingDirectionalKeysAndAuthenticatedFrame() throws Exception {
        SharedPassphrase passphrase = SharedPassphrase.from("correct horse battery staple");
        byte[] key = passphrase.keyForServer("lobby");
        HandshakeMessages.ClientState client = HandshakeMessages.begin("lobby", key, CLOCK);
        HandshakeMessages.IssuedChallenge issued = HandshakeMessages.challenge(client.hello(), key, CLOCK);
        HandshakeMessages.Proof proof = HandshakeMessages.prove(client, issued.challenge(), CLOCK);
        HandshakeMessages.Accepted accepted = HandshakeMessages.verifyProof(issued, proof, 42L, CLOCK);
        SessionKeys paperKeys = HandshakeMessages.verifyAccepted(client, issued.challenge(), accepted, CLOCK);
        SessionKeys velocityKeys = HandshakeMessages.keys(issued);
        SecureFrameCodec sender = new SecureFrameCodec(accepted.sessionId(), accepted.epoch(), paperKeys, true, CLOCK);
        SecureFrameCodec receiver = new SecureFrameCodec(accepted.sessionId(), accepted.epoch(), velocityKeys, true, CLOCK);
        UUID request = UUID.randomUUID();
        byte[] encoded = sender.encode(ProtocolType.CHAT_UP, request, "hello".getBytes(), 1_000);
        SecureFrameCodec.Decoded decoded = receiver.decode(encoded);
        assertEquals(ProtocolType.CHAT_UP, decoded.type());
        assertEquals(request, decoded.requestId());
        assertArrayEquals("hello".getBytes(), decoded.payload());
    }

    @Test void tamperingAndReplayAreRejectedWithoutFallback() throws Exception {
        SessionKeys keys = SessionKeys.derive(new byte[32], UUID.randomUUID(), new byte[32], new byte[32]);
        UUID session = UUID.randomUUID();
        SecureFrameCodec sender = new SecureFrameCodec(session, 2, keys, true, CLOCK);
        SecureFrameCodec receiver = new SecureFrameCodec(session, 2, keys, true, CLOCK);
        byte[] valid = sender.encode(ProtocolType.CHAT_UP, UUID.randomUUID(), new byte[] {1}, 1_000);
        byte[] tampered = valid.clone(); tampered[tampered.length - 1] ^= 1;
        assertEquals(ProtocolException.Code.AUTHENTICATION_FAILED,
                assertThrows(ProtocolException.class, () -> receiver.decode(tampered)).code());
        receiver.decode(valid);
        assertEquals(ProtocolException.Code.REPLAYED,
                assertThrows(ProtocolException.class, () -> receiver.decode(valid)).code());
    }

    @Test void challengeCannotBeAlteredOrUsedForAnotherClient() throws Exception {
        byte[] key = SharedPassphrase.from("correct horse battery staple").keyForServer("lobby");
        HandshakeMessages.ClientState client = HandshakeMessages.begin("lobby", key, CLOCK);
        HandshakeMessages.IssuedChallenge issued = HandshakeMessages.challenge(client.hello(), key, CLOCK);
        HandshakeMessages.Challenge changed = new HandshakeMessages.Challenge("other", issued.challenge().sessionId(),
                issued.challenge().issuedAt(), issued.challenge().expiresAt(), issued.challenge().clientNonce(),
                issued.challenge().serverNonce(), issued.challenge().mac());
        assertEquals(ProtocolException.Code.AUTHENTICATION_FAILED,
                assertThrows(ProtocolException.class, () -> HandshakeMessages.prove(client, changed, CLOCK)).code());
    }

    @Test void handshakeRejectsMalformedUtf8AndTruncatedMac() {
        byte[] key = SharedPassphrase.from("correct horse battery staple").keyForServer("a");
        HandshakeMessages.Hello hello = HandshakeMessages.begin("a", key, CLOCK).hello();
        byte[] malformed = HandshakeMessages.encode(hello);
        malformed[1] = (byte) 0xC0;
        assertEquals(ProtocolException.Code.MALFORMED,
                assertThrows(ProtocolException.class, () -> HandshakeMessages.decodeHello(malformed)).code());

        byte[] encoded = HandshakeMessages.encode(hello);
        byte[] truncated = java.util.Arrays.copyOf(encoded, encoded.length - 1);
        assertEquals(ProtocolException.Code.MALFORMED,
                assertThrows(ProtocolException.class, () -> HandshakeMessages.decodeHello(truncated)).code());
    }
}
