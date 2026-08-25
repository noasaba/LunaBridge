package dev.lunabridge.discord;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable Discord-only configuration supplied by a platform adapter. */
public record DiscordSettings(
        String token,
        Map<String, String> discordChannelToLunaChatChannelId,
        Map<String, String> options) {
    public DiscordSettings {
        token = Objects.requireNonNull(token, "token").trim();
        discordChannelToLunaChatChannelId = Map.copyOf(new LinkedHashMap<>(
                Objects.requireNonNull(discordChannelToLunaChatChannelId, "channel mappings")));
        options = Map.copyOf(new LinkedHashMap<>(Objects.requireNonNull(options, "options")));
    }

    public String option(String key, String fallback) {
        return options.getOrDefault(key, fallback);
    }
}
