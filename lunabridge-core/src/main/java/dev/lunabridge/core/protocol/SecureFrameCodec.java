package dev.lunabridge.core.protocol;

import dev.lunabridge.core.crypto.ReplayWindow;
import dev.lunabridge.core.crypto.SessionKeys;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/** AES-256-GCM session framing. Header is authenticated additional data and sequence is single-use. */
public final class SecureFrameCodec implements AutoCloseable {
    private static final int MAGIC = 0x4C425232; // LBR2
    private static final int HEADER_BYTES = 76;
    private static final int TAG_BYTES = 16;
    private static final int MAX_PAYLOAD = 28 * 1024;
    private static final long MAX_LIFETIME_MILLIS = 5 * 60 * 1000;

    public record FrameIdentity(UUID sessionId, long epoch, long sequence) { }
    public record Decoded(FrameIdentity frameIdentity, ProtocolType type, UUID logicalMessageId, byte[] payload) {
        public Decoded { payload = payload.clone(); }
        @Override public byte[] payload() { return payload.clone(); }
    }

    private final UUID sessionId;
    private final long epoch;
    private final Instant sessionExpiresAt;
    private final byte[] key;
    private final byte[] noncePrefix;
    private final ReplayWindow inboundReplay = new ReplayWindow();
    private final Clock clock;
    private long nextSequence;
    private boolean closed;

    public SecureFrameCodec(UUID sessionId, long epoch, Instant sessionExpiresAt, SessionKeys keys,
                            boolean clientToServerDirection, Clock clock) {
        if (epoch < 0 || !sessionExpiresAt.isAfter(clock.instant())) throw new IllegalArgumentException("invalid session bounds");
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.epoch = epoch;
        this.sessionExpiresAt = sessionExpiresAt;
        this.key = keys.key(clientToServerDirection);
        this.noncePrefix = keys.noncePrefix(clientToServerDirection);
        this.clock = clock;
    }

    public synchronized byte[] encode(ProtocolType type, UUID logicalMessageId, byte[] payload, long lifetimeMillis)
            throws ProtocolException {
        requireUsable();
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(logicalMessageId, "logicalMessageId");
        Objects.requireNonNull(payload, "payload");
        if (payload.length > MAX_PAYLOAD || lifetimeMillis < 1 || lifetimeMillis > MAX_LIFETIME_MILLIS) {
            throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "payload or lifetime exceeds protocol limit");
        }
        if (nextSequence == Long.MAX_VALUE) throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "sequence exhausted");
        long now = clock.millis();
        long sequence = nextSequence++;
        long expiry = Math.min(now + lifetimeMillis, sessionExpiresAt.toEpochMilli());
        if (expiry <= now) throw new ProtocolException(ProtocolException.Code.EXPIRED, "session expired");
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        header.putInt(MAGIC).putShort((short) 2).putShort((short) type.id()).putInt(payload.length + TAG_BYTES);
        putUuid(header, sessionId);
        header.putLong(epoch).putLong(sequence).putLong(now).putLong(expiry);
        putUuid(header, logicalMessageId);
        byte[] encrypted = crypt(Cipher.ENCRYPT_MODE, key, nonce(sequence), header.array(), payload);
        return ByteBuffer.allocate(HEADER_BYTES + encrypted.length).put(header.array()).put(encrypted).array();
    }

    public synchronized Decoded decode(byte[] frame) throws ProtocolException {
        requireUsable();
        if (frame == null || frame.length < HEADER_BYTES + TAG_BYTES || frame.length > HEADER_BYTES + MAX_PAYLOAD + TAG_BYTES) {
            throw new ProtocolException(ProtocolException.Code.MALFORMED, "invalid frame length");
        }
        ByteBuffer bytes = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN);
        if (bytes.getInt() != MAGIC || bytes.getShort() != 2) throw new ProtocolException(ProtocolException.Code.MALFORMED, "unsupported frame");
        ProtocolType type = ProtocolType.fromId(Short.toUnsignedInt(bytes.getShort()));
        int encryptedLength = bytes.getInt();
        UUID receivedSession = readUuid(bytes);
        long receivedEpoch = bytes.getLong();
        long sequence = bytes.getLong();
        long issuedAt = bytes.getLong();
        long expiresAt = bytes.getLong();
        UUID logicalMessageId = readUuid(bytes);
        if (encryptedLength != frame.length - HEADER_BYTES || !receivedSession.equals(sessionId) || receivedEpoch != epoch) {
            throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "session or frame size mismatch");
        }
        long now = clock.millis();
        if (expiresAt <= now || issuedAt > now + 30_000 || expiresAt - issuedAt > MAX_LIFETIME_MILLIS
                || expiresAt > sessionExpiresAt.toEpochMilli()) {
            throw new ProtocolException(ProtocolException.Code.EXPIRED, "frame is expired or out of validity window");
        }
        byte[] header = new byte[HEADER_BYTES];
        System.arraycopy(frame, 0, header, 0, HEADER_BYTES);
        byte[] encrypted = new byte[encryptedLength];
        System.arraycopy(frame, HEADER_BYTES, encrypted, 0, encryptedLength);
        byte[] payload = crypt(Cipher.DECRYPT_MODE, key, nonce(sequence), header, encrypted);
        if (!inboundReplay.admit(sequence)) throw new ProtocolException(ProtocolException.Code.REPLAYED, "replayed frame");
        return new Decoded(new FrameIdentity(sessionId, epoch, sequence), type, logicalMessageId, payload);
    }

    @Override public synchronized void close() {
        if (closed) return;
        Arrays.fill(key, (byte) 0);
        Arrays.fill(noncePrefix, (byte) 0);
        closed = true;
    }

    private void requireUsable() throws ProtocolException {
        if (closed) throw new ProtocolException(ProtocolException.Code.INVALID_SESSION, "secure frame codec is closed");
        if (!sessionExpiresAt.isAfter(clock.instant())) throw new ProtocolException(ProtocolException.Code.EXPIRED, "session expired");
    }

    private byte[] nonce(long sequence) {
        ByteBuffer nonce = ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN);
        nonce.put(noncePrefix).putLong(sequence);
        return nonce.array();
    }
    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] content) throws ProtocolException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad);
            return cipher.doFinal(content);
        } catch (GeneralSecurityException security) {
            throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED, "encrypted frame did not authenticate");
        }
    }
    private static void putUuid(ByteBuffer bytes, UUID id) { bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(ByteBuffer bytes) { return new UUID(bytes.getLong(), bytes.getLong()); }
}
