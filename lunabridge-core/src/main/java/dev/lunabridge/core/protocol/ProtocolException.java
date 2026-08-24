package dev.lunabridge.core.protocol;

public final class ProtocolException extends Exception {
    private static final long serialVersionUID = 1L;
    public enum Code { MALFORMED, EXPIRED, AUTHENTICATION_FAILED, REPLAYED, UNKNOWN_TYPE, INVALID_SESSION, LIMIT_EXCEEDED }
    private final Code code;
    public ProtocolException(Code code, String message) { super(message); this.code = code; }
    public Code code() { return code; }
}
