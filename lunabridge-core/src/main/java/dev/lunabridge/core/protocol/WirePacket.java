package dev.lunabridge.core.protocol;

import java.util.Arrays;

/** Minimal outer discriminator for plugin-message carriers before a session exists. */
public final class WirePacket {
    public static final int MAX_BYTES = 32 * 1024;
    private WirePacket() { }
    public static byte[] wrap(ProtocolType type, byte[] payload) {
        if (payload.length > MAX_BYTES - 1) throw new IllegalArgumentException("packet too large");
        byte[] packet = new byte[payload.length + 1]; packet[0] = (byte) type.id(); System.arraycopy(payload, 0, packet, 1, payload.length); return packet;
    }
    public static Packet unwrap(byte[] packet) throws ProtocolException {
        if (packet == null || packet.length < 2 || packet.length > MAX_BYTES) throw new ProtocolException(ProtocolException.Code.MALFORMED, "invalid outer packet");
        return new Packet(ProtocolType.fromId(Byte.toUnsignedInt(packet[0])), Arrays.copyOfRange(packet, 1, packet.length));
    }
    public record Packet(ProtocolType type, byte[] payload) { public Packet { payload = payload.clone(); } }
}
