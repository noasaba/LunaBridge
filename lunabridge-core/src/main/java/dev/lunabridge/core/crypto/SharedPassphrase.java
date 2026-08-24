package dev.lunabridge.core.crypto;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.Objects;

/** Derives internal credentials from the one operator-facing shared passphrase. */
public final class SharedPassphrase {
    private static final byte[] SALT = "lunabridge/v1/shared-pass".getBytes(StandardCharsets.UTF_8);
    private final byte[] master;

    private SharedPassphrase(byte[] master) { this.master = master; }

    public static SharedPassphrase from(String passphrase) {
        Objects.requireNonNull(passphrase, "passphrase");
        String normalized = Normalizer.normalize(passphrase, Normalizer.Form.NFKC);
        if (normalized.length() < 16) throw new IllegalArgumentException("shared-pass must be at least 16 characters");
        return new SharedPassphrase(HkdfSha256.extract(SALT, normalized.getBytes(StandardCharsets.UTF_8)));
    }

    public byte[] keyForServer(String serverId) {
        if (serverId == null || !serverId.matches("[A-Za-z0-9._-]{1,64}")) {
            throw new IllegalArgumentException("invalid server id");
        }
        return HkdfSha256.expand(master, "lunabridge/v1/server/" + serverId, 32);
    }

    public void destroy() { Arrays.fill(master, (byte) 0); }

    @Override public String toString() { return "SharedPassphrase[redacted]"; }
}
