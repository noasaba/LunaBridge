package dev.lunabridge.core.protocol;

import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

/** Strict UTF-8 wire form; platform Components and JDA values never cross this boundary. */
public final class BridgeMessageCodec {
    private BridgeMessageCodec() { }

    public static byte[] encode(BridgeMessage message) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeShort(1);
            uuid(out, message.id()); uuid(out, message.traceId()); out.writeByte(message.origin().ordinal());
            string(out, message.bridgeChannel(), 64); string(out, message.lunaChannelName(), 128);
            out.writeBoolean(message.authorId() != null); if (message.authorId() != null) uuid(out, message.authorId());
            string(out, message.authorName(), 128); string(out, message.content(), 16 * 1024);
            string(out, message.sourceServer(), 64); out.writeLong(message.issuedAt().toEpochMilli()); out.writeLong(message.expiresAt().toEpochMilli());
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new IllegalStateException(impossible); }
    }

    public static BridgeMessage decode(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length > 28 * 1024) throw new ProtocolException(ProtocolException.Code.LIMIT_EXCEEDED, "message payload too large");
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            if (in.readUnsignedShort() != 1) throw new IOException("unsupported message version");
            UUID id = uuid(in); UUID trace = uuid(in); int origin = in.readUnsignedByte();
            if (origin >= BridgeOrigin.values().length) throw new IOException("invalid origin");
            String bridge = string(in, 64); String luna = string(in, 128);
            UUID author = in.readBoolean() ? uuid(in) : null;
            BridgeMessage message = new BridgeMessage(id, trace, BridgeOrigin.values()[origin], bridge, luna, author,
                    string(in, 128), string(in, 16 * 1024), string(in, 64), Instant.ofEpochMilli(in.readLong()), Instant.ofEpochMilli(in.readLong()));
            if (in.available() != 0) throw new IOException("trailing message bytes");
            return message;
        } catch (IOException | IllegalArgumentException error) {
            throw new ProtocolException(ProtocolException.Code.MALFORMED, "invalid bridge message");
        }
    }

    private static void string(DataOutputStream out, String text, int maxBytes) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes || bytes.length > 65_535) throw new IOException("string too long");
        out.writeShort(bytes.length); out.write(bytes);
    }
    private static String string(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort(); if (length > maxBytes) throw new IOException("string too long");
        byte[] bytes = in.readNBytes(length); if (bytes.length != length) throw new IOException("truncated string");
        return StrictUtf8.decode(bytes);
    }
    private static void uuid(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
    private static UUID uuid(DataInputStream in) throws IOException { return new UUID(in.readLong(), in.readLong()); }
}
