package dev.lunabridge.core.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** RFC 5869 HKDF-SHA-256, kept platform independent and with no secret-bearing toString values. */
public final class HkdfSha256 {
    private HkdfSha256() { }

    public static byte[] extract(byte[] salt, byte[] ikm) {
        return hmac(salt, ikm);
    }

    public static byte[] expand(byte[] prk, String label, int length) {
        if (length < 1 || length > 255 * 32) throw new IllegalArgumentException("invalid HKDF length");
        byte[] info = label.getBytes(StandardCharsets.UTF_8);
        byte[] output = new byte[length];
        byte[] previous = new byte[0];
        int written = 0;
        for (int counter = 1; written < length; counter++) {
            byte[] input = Arrays.copyOf(previous, previous.length + info.length + 1);
            System.arraycopy(info, 0, input, previous.length, info.length);
            input[input.length - 1] = (byte) counter;
            previous = hmac(prk, input);
            int take = Math.min(previous.length, length - written);
            System.arraycopy(previous, 0, output, written, take);
            written += take;
        }
        return output;
    }

    public static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException exception) {
            throw new IllegalStateException("JRE has no HmacSHA256", exception);
        }
    }
}
