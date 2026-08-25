package dev.lunabridge.core.protocol;

import dev.lunabridge.core.model.BridgeChannelMapping;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Authenticated, canonical backend bridge capabilities carried by the handshake. */
public final class ChannelManifestCodec {
    public static final int MAX_BYTES = 4 * 1024;
    private static final int MAX_CHANNELS = 256;

    private ChannelManifestCodec() { }

    public static byte[] encode(Map<String, String> channels) {
        BridgeChannelMapping validated = new BridgeChannelMapping(channels);
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            Map<String, String> sorted = new TreeMap<>(validated.asBridgeKeyToLunaName());
            if (sorted.size() > MAX_CHANNELS) throw new IllegalArgumentException("too many bridge channels");
            out.writeShort(1);
            out.writeShort(sorted.size());
            for (Map.Entry<String, String> entry : sorted.entrySet()) {
                writeString(out, entry.getKey(), 64);
                writeString(out, entry.getValue(), 128);
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_BYTES) throw new IllegalArgumentException("channel manifest is too large");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static Map<String, String> decode(byte[] encoded) throws ProtocolException {
        if (encoded == null || encoded.length > MAX_BYTES) {
            throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "channel manifest is too large");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (in.readUnsignedShort() != 1) throw new IOException("unsupported channel manifest");
            int count = in.readUnsignedShort();
            if (count > MAX_CHANNELS) throw new IOException("too many bridge channels");
            Map<String, String> channels = new LinkedHashMap<>();
            for (int index = 0; index < count; index++) {
                String key = readString(in, 64);
                String lunaName = readString(in, 128);
                if (channels.put(key, lunaName) != null) throw new IOException("duplicate bridge channel");
            }
            if (in.available() != 0) throw new IOException("trailing channel manifest bytes");
            return new BridgeChannelMapping(channels).asBridgeKeyToLunaName();
        } catch (IOException | IllegalArgumentException invalid) {
            throw new ProtocolException(ProtocolException.Code.MALFORMED, "invalid channel manifest");
        }
    }

    public static void requireCompatible(Map<String, String> existing, Map<String, String> candidate)
            throws ProtocolException {
        for (Map.Entry<String, String> channel : candidate.entrySet()) {
            String current = existing.get(channel.getKey());
            if (current != null && !current.equals(channel.getValue())) {
                throw new ProtocolException(ProtocolException.Code.AUTHENTICATION_FAILED,
                        "bridge mapping conflict for " + channel.getKey());
            }
        }
    }

    private static void writeString(DataOutputStream out, String value, int limit) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > limit) throw new IOException("manifest string too long");
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int limit) throws IOException {
        int length = in.readUnsignedShort();
        if (length > limit) throw new IOException("manifest string too long");
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("truncated manifest string");
        return StrictUtf8.decode(bytes);
    }
}
