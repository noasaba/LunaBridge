package dev.lunabridge.core.protocol;

/** Numeric ids are compatibility contracts; never serialize enum names. */
public enum ProtocolType {
    HELLO(1), CHALLENGE(2), PROOF(3), ACCEPT(4),
    CHAT_UP(10), CHAT_DOWN(11), ACK(12), NOTIFICATION(13);
    private final int id;
    ProtocolType(int id) { this.id = id; }
    public int id() { return id; }
    public static ProtocolType fromId(int id) throws ProtocolException {
        for (ProtocolType type : values()) if (type.id == id) return type;
        throw new ProtocolException(ProtocolException.Code.UNKNOWN_TYPE, "unknown protocol type");
    }
}
