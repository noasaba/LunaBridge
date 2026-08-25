package dev.lunabridge.core.protocol;

import dev.lunabridge.core.crypto.HkdfSha256;
import dev.lunabridge.core.crypto.SessionKeys;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.UUID;

/** Bounded HMAC handshake. A session can only exist after hello, challenge and proof validate. */
public final class HandshakeMessages {
    private static final int VERSION = 2;
    private static final int NONCE_BYTES = 32;
    private static final int MAC_BYTES = 32;
    private static final long MAX_SKEW_MILLIS = 30_000;
    private static final long CHALLENGE_TTL_MILLIS = 30_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private HandshakeMessages() { }

    public record Hello(String serverId, long issuedAt, byte[] nonce, byte[] capabilities, byte[] mac) {
        public Hello { nonce = nonce.clone(); capabilities = capabilities.clone(); mac = mac.clone(); }
    }
    public record Challenge(String serverId, UUID sessionId, long issuedAt, long expiresAt,
                            byte[] clientNonce, byte[] serverNonce, byte[] mac) {
        public Challenge { clientNonce = clientNonce.clone(); serverNonce = serverNonce.clone(); mac = mac.clone(); }
    }
    public record Proof(UUID sessionId, long issuedAt, byte[] mac) { public Proof { mac = mac.clone(); } }
    public record Accepted(UUID sessionId, long epoch, long expiresAt, byte[] mac) { public Accepted { mac = mac.clone(); } }
    public record ClientState(Hello hello, byte[] serverKey) {
        public ClientState { serverKey = serverKey.clone(); }
        public void destroy() { Arrays.fill(serverKey, (byte) 0); }
    }
    public record IssuedChallenge(Challenge challenge, byte[] serverKey) {
        public IssuedChallenge { serverKey = serverKey.clone(); }
        public void destroy() { Arrays.fill(serverKey, (byte) 0); }
    }

    public static ClientState begin(String serverId, byte[] serverKey, byte[] capabilities, Clock clock) {
        if (capabilities == null || capabilities.length > ChannelManifestCodec.MAX_BYTES) {
            throw new IllegalArgumentException("invalid handshake capabilities");
        }
        byte[] nonce = random(NONCE_BYTES);
        long now = clock.millis();
        byte[] mac = mac(serverKey, "hello", serverId, now, nonce, capabilities);
        return new ClientState(new Hello(serverId, now, nonce, capabilities, mac), serverKey);
    }

    public static IssuedChallenge challenge(Hello hello, byte[] expectedServerKey, Clock clock) throws ProtocolException {
        requireFresh(hello.issuedAt(), clock.millis(), MAX_SKEW_MILLIS);
        if (!MessageDigest.isEqual(hello.mac(), mac(expectedServerKey, "hello", hello.serverId(), hello.issuedAt(),
                hello.nonce(), hello.capabilities()))) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "invalid hello MAC");
        }
        long now = clock.millis();
        Challenge challenge = new Challenge(hello.serverId(), UUID.randomUUID(), now, now + CHALLENGE_TTL_MILLIS,
                hello.nonce(), random(NONCE_BYTES), new byte[MAC_BYTES]);
        // Challenge MAC includes session and server nonce; construct once those values exist.
        byte[] actualMac = mac(expectedServerKey, "challenge", challenge.serverId(), challenge.sessionId(),
                challenge.issuedAt(), challenge.expiresAt(), challenge.clientNonce(), challenge.serverNonce());
        return new IssuedChallenge(new Challenge(challenge.serverId(), challenge.sessionId(), challenge.issuedAt(),
                challenge.expiresAt(), challenge.clientNonce(), challenge.serverNonce(), actualMac), expectedServerKey);
    }

    public static Proof prove(ClientState state, Challenge challenge, Clock clock) throws ProtocolException {
        requireFresh(challenge.issuedAt(), clock.millis(), MAX_SKEW_MILLIS);
        if (!state.hello().serverId().equals(challenge.serverId()) || !Arrays.equals(state.hello().nonce(), challenge.clientNonce())) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "challenge does not bind hello");
        }
        byte[] expected = mac(state.serverKey(), "challenge", challenge.serverId(), challenge.sessionId(),
                challenge.issuedAt(), challenge.expiresAt(), challenge.clientNonce(), challenge.serverNonce());
        if (!MessageDigest.isEqual(expected, challenge.mac())) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "invalid challenge MAC");
        }
        long now = clock.millis();
        return new Proof(challenge.sessionId(), now, mac(state.serverKey(), "proof", challenge.sessionId(), now,
                challenge.clientNonce(), challenge.serverNonce()));
    }

    public static Accepted verifyProof(IssuedChallenge issued, Proof proof, long epoch, Clock clock) throws ProtocolException {
        Challenge challenge = issued.challenge();
        if (!challenge.sessionId().equals(proof.sessionId()) || clock.millis() > challenge.expiresAt()) {
            throw new ProtocolException(ProtocolException.Code.EXPIRED, "challenge expired or session mismatch");
        }
        requireFresh(proof.issuedAt(), clock.millis(), MAX_SKEW_MILLIS);
        byte[] expected = mac(issued.serverKey(), "proof", proof.sessionId(), proof.issuedAt(),
                challenge.clientNonce(), challenge.serverNonce());
        if (!MessageDigest.isEqual(expected, proof.mac())) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "invalid proof MAC");
        }
        long expiry = clock.millis() + 300_000;
        return new Accepted(proof.sessionId(), epoch, expiry, mac(issued.serverKey(), "accept", proof.sessionId(), epoch, expiry));
    }

    public static SessionKeys verifyAccepted(ClientState state, Challenge challenge, Accepted accepted, Clock clock)
            throws ProtocolException {
        if (!challenge.sessionId().equals(accepted.sessionId()) || accepted.expiresAt() <= clock.millis()) {
            throw new ProtocolException(ProtocolException.Code.EXPIRED, "accepted session expired or mismatched");
        }
        byte[] expected = mac(state.serverKey(), "accept", accepted.sessionId(), accepted.epoch(), accepted.expiresAt());
        if (!MessageDigest.isEqual(expected, accepted.mac())) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "invalid accept MAC");
        }
        return SessionKeys.derive(state.serverKey(), accepted.sessionId(), challenge.clientNonce(), challenge.serverNonce());
    }

    public static SessionKeys keys(IssuedChallenge issued) {
        Challenge c = issued.challenge();
        return SessionKeys.derive(issued.serverKey(), c.sessionId(), c.clientNonce(), c.serverNonce());
    }

    public static byte[] encode(Hello value) { return encode(out -> { writeString(out, value.serverId()); out.writeLong(value.issuedAt()); writeBytes(out, value.nonce()); writeBlob(out, value.capabilities()); writeBytes(out, value.mac()); }); }
    public static byte[] encode(Challenge value) { return encode(out -> { writeString(out, value.serverId()); writeUuid(out, value.sessionId()); out.writeLong(value.issuedAt()); out.writeLong(value.expiresAt()); writeBytes(out, value.clientNonce()); writeBytes(out, value.serverNonce()); writeBytes(out, value.mac()); }); }
    public static byte[] encode(Proof value) { return encode(out -> { writeUuid(out, value.sessionId()); out.writeLong(value.issuedAt()); writeBytes(out, value.mac()); }); }
    public static byte[] encode(Accepted value) { return encode(out -> { writeUuid(out, value.sessionId()); out.writeLong(value.epoch()); out.writeLong(value.expiresAt()); writeBytes(out, value.mac()); }); }

    public static Hello decodeHello(byte[] bytes) throws ProtocolException { return decode(bytes, in -> new Hello(readString(in, 64), in.readLong(), readBytes(in, NONCE_BYTES), readBlob(in, ChannelManifestCodec.MAX_BYTES), readBytes(in, MAC_BYTES))); }
    public static Challenge decodeChallenge(byte[] bytes) throws ProtocolException { return decode(bytes, in -> new Challenge(readString(in, 64), readUuid(in), in.readLong(), in.readLong(), readBytes(in, NONCE_BYTES), readBytes(in, NONCE_BYTES), readBytes(in, MAC_BYTES))); }
    public static Proof decodeProof(byte[] bytes) throws ProtocolException { return decode(bytes, in -> new Proof(readUuid(in), in.readLong(), readBytes(in, MAC_BYTES))); }
    public static Accepted decodeAccepted(byte[] bytes) throws ProtocolException { return decode(bytes, in -> new Accepted(readUuid(in), in.readLong(), in.readLong(), readBytes(in, MAC_BYTES))); }

    private static byte[] random(int size) { byte[] bytes = new byte[size]; RANDOM.nextBytes(bytes); return bytes; }
    private static void requireFresh(long issued, long now, long maximum) throws ProtocolException {
        if (issued > now + maximum || issued < now - maximum) throw new ProtocolException(ProtocolException.Code.EXPIRED, "stale handshake");
    }
    private static byte[] mac(byte[] key, Object... parts) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(VERSION);
            for (Object part : parts) {
                if (part instanceof String string) writeString(out, string);
                else if (part instanceof Long number) out.writeLong(number);
                else if (part instanceof UUID id) writeUuid(out, id);
                else if (part instanceof byte[] array) { out.writeInt(array.length); out.write(array); }
                else throw new IllegalArgumentException("unsupported MAC part");
            }
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
        return HkdfSha256.hmac(key, bytes.toByteArray());
    }
    private interface Writer { void write(DataOutputStream out) throws IOException; }
    private interface Reader<T> { T read(DataInputStream in) throws IOException; }
    private static byte[] encode(Writer writer) { try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) { writer.write(out); return bytes.toByteArray(); } catch (IOException impossible) { throw new IllegalStateException(impossible); } }
    private static <T> T decode(byte[] bytes, Reader<T> reader) throws ProtocolException { if (bytes == null) throw new ProtocolException(ProtocolException.Code.MALFORMED, "missing handshake"); if (bytes.length > 8192) throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "handshake too large"); try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) { T result = reader.read(in); if (in.available() != 0) throw new IOException("trailing bytes"); return result; } catch (IOException error) { throw new ProtocolException(ProtocolException.Code.MALFORMED, error.getMessage()); } }
    private static void writeString(DataOutputStream out, String text) throws IOException { byte[] bytes = text.getBytes(StandardCharsets.UTF_8); if (bytes.length > 255) throw new IOException("string too large"); out.writeByte(bytes.length); out.write(bytes); }
    private static String readString(DataInputStream in, int limit) throws IOException { int length = in.readUnsignedByte(); if (length > limit) throw new IOException("string too large"); byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("truncated string"); return StrictUtf8.decode(bytes); }
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException { if (bytes.length > 255) throw new IOException("bytes too large"); out.writeByte(bytes.length); out.write(bytes); }
    private static byte[] readBytes(DataInputStream in, int expected) throws IOException { int length = in.readUnsignedByte(); if (length != expected) throw new IOException("invalid byte length"); byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("truncated bytes"); return bytes; }
    private static void writeBlob(DataOutputStream out, byte[] bytes) throws IOException { if (bytes.length > ChannelManifestCodec.MAX_BYTES) throw new IOException("blob too large"); out.writeShort(bytes.length); out.write(bytes); }
    private static byte[] readBlob(DataInputStream in, int limit) throws IOException { int length = in.readUnsignedShort(); if (length > limit) throw new IOException("blob too large"); byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("truncated blob"); return bytes; }
    private static void writeUuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
