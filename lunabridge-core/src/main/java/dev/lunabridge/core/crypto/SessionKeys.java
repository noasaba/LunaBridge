package dev.lunabridge.core.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Independent AES-GCM keys and nonce prefixes make directions non-interchangeable. */
public final class SessionKeys {
    private final byte[] clientToServer;
    private final byte[] serverToClient;
    private final byte[] clientPrefix;
    private final byte[] serverPrefix;

    private SessionKeys(byte[] clientToServer, byte[] serverToClient, byte[] clientPrefix, byte[] serverPrefix) {
        this.clientToServer = clientToServer;
        this.serverToClient = serverToClient;
        this.clientPrefix = clientPrefix;
        this.serverPrefix = serverPrefix;
    }

    public static SessionKeys derive(byte[] serverKey, UUID sessionId, byte[] clientNonce, byte[] serverNonce) {
        ByteBuffer transcript = ByteBuffer.allocate(16 + clientNonce.length + serverNonce.length);
        transcript.putLong(sessionId.getMostSignificantBits()).putLong(sessionId.getLeastSignificantBits());
        transcript.put(clientNonce).put(serverNonce);
        byte[] prk = HkdfSha256.extract(serverKey, transcript.array());
        return new SessionKeys(
                HkdfSha256.expand(prk, "lunabridge/v1/c2s/key", 32),
                HkdfSha256.expand(prk, "lunabridge/v1/s2c/key", 32),
                HkdfSha256.expand(prk, "lunabridge/v1/c2s/nonce", 4),
                HkdfSha256.expand(prk, "lunabridge/v1/s2c/nonce", 4));
    }

    public byte[] key(boolean clientToServerDirection) {
        return (clientToServerDirection ? clientToServer : serverToClient).clone();
    }

    public byte[] noncePrefix(boolean clientToServerDirection) {
        return (clientToServerDirection ? clientPrefix : serverPrefix).clone();
    }

    public void destroy() {
        Arrays.fill(clientToServer, (byte) 0);
        Arrays.fill(serverToClient, (byte) 0);
        Arrays.fill(clientPrefix, (byte) 0);
        Arrays.fill(serverPrefix, (byte) 0);
    }

    @Override public String toString() { return "SessionKeys[redacted]"; }
}
