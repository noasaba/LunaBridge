package dev.lunabridge.core.model;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Platform-neutral message accepted by the bridge after the chat engine has made its decision. */
public record BridgeMessage(
        UUID id,
        UUID traceId,
        BridgeOrigin origin,
        String bridgeChannel,
        String lunaChannelName,
        UUID authorId,
        String authorName,
        String content,
        String sourceServer,
        Instant issuedAt,
        Instant expiresAt) {

    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final int MAX_CONTENT_BYTES = 16 * 1024;

    public BridgeMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(traceId, "traceId");
        Objects.requireNonNull(origin, "origin");
        requireKey(bridgeChannel, "bridgeChannel");
        requireText(lunaChannelName, "lunaChannelName", 128);
        requireText(authorName, "authorName", 128);
        requireText(sourceServer, "sourceServer", 64);
        Objects.requireNonNull(content, "content");
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new IllegalArgumentException("content exceeds 16 KiB");
        }
        Objects.requireNonNull(issuedAt, "issuedAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiry must be after issue time");
        }
    }

    public boolean hasMinecraftAuthor() {
        return authorId != null;
    }

    private static void requireKey(String value, String name) {
        if (value == null || !KEY.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a stable lower-case key");
        }
    }

    private static void requireText(String value, String name, int maxBytes) {
        if (value == null || value.isBlank() || value.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new IllegalArgumentException(name + " is missing or too long");
        }
    }
}
