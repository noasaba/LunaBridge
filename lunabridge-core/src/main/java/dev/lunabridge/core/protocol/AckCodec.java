package dev.lunabridge.core.protocol;

import java.nio.ByteBuffer;
import java.util.UUID;

public final class AckCodec {
    private AckCodec() { }
    public static byte[] encode(UUID id) { return ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()).array(); }
    public static UUID decode(byte[] bytes) throws ProtocolException {
        if (bytes == null || bytes.length != 16) throw new ProtocolException(ProtocolException.Code.MALFORMED, "bad ack");
        ByteBuffer buffer = ByteBuffer.wrap(bytes); return new UUID(buffer.getLong(), buffer.getLong());
    }
}
