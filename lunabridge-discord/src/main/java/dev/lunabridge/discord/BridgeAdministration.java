package dev.lunabridge.discord;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.NetworkState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Platform-independent setup validation and concise operator diagnostics. */
public final class BridgeAdministration {
    private static final List<String> NOTIFICATION_TYPES = List.of(
            "startup", "shutdown", "join", "quit", "first-login", "login", "server-switch");
    private BridgeAdministration() { }

    public static ChannelDescriptor resolveSetup(LunaChatIntegrationApi api, String discordChannelId,
                                                  String channelNameOrAlias) {
        if (!discordChannelId.matches("[0-9]{5,32}")) throw new IllegalArgumentException("invalid Discord channel id");
        ChannelDescriptor channel = api.channels().findByNameOrAlias(channelNameOrAlias).orElseThrow(
                () -> new IllegalArgumentException("LunaChat channel or alias was not found: " + channelNameOrAlias));
        if (!channel.acceptsExternalMessages()) {
            throw new IllegalArgumentException("LunaChat channel " + channel.name()
                    + " does not accept external messages; enable accepts_external_messages first");
        }
        return channel;
    }

    public static List<String> doctor(LunaChatIntegrationApi api, DiscordSettings settings,
                                      boolean gatewayPresent, boolean gatewayReady) {
        List<String> lines = new ArrayList<>();
        lines.add("OK LunaChat API " + api.apiVersion() + " role=" + api.runtimeRole());
        var network = api.networkStatus().current();
        String networkLevel = switch (network.state()) {
            case READY -> "OK";
            case RELOADING -> "WAIT";
            case DEGRADED, UNAVAILABLE, SHUTTING_DOWN -> "FAIL";
        };
        lines.add(networkLevel + " network " + network.state() + " (" + network.diagnosticCode() + ")");
        lines.add((settings.token().isBlank() || settings.token().equals("PUT_DISCORD_BOT_TOKEN_HERE")
                ? "FAIL Discord token is not configured" : "OK Discord token configured"));
        lines.add(gatewayReady ? "OK Discord gateway ready"
                : gatewayPresent ? "WAIT Discord gateway is connecting" : "FAIL Discord gateway unavailable");
        if (settings.discordChannelToLunaChatChannelId().isEmpty()) lines.add("FAIL no Discord channel mappings");
        settings.discordChannelToLunaChatChannelId().forEach((discord, stableId) -> {
            var descriptor = api.channels().find(new ChannelId(stableId));
            if (descriptor.isEmpty()) lines.add("FAIL " + discord + " -> unknown ChannelId " + stableId + " (not routed)");
            else if (!descriptor.orElseThrow().acceptsExternalMessages()) {
                lines.add("FAIL " + discord + " -> " + descriptor.orElseThrow().name() + " external messages disabled");
            } else lines.add("OK " + discord + " -> " + descriptor.orElseThrow().name() + " (" + stableId + ")");
        });
        validateOptions(settings, lines);
        return List.copyOf(lines);
    }

    public static DiscordSettings routableSettings(LunaChatIntegrationApi api, DiscordSettings settings) {
        Map<String, String> mappings = new LinkedHashMap<>();
        settings.discordChannelToLunaChatChannelId().forEach((discord, stableId) ->
                api.channels().find(new ChannelId(stableId))
                        .filter(channel -> channel.acceptsExternalMessages())
                        .ifPresent(channel -> mappings.put(discord, stableId)));
        return new DiscordSettings(settings.token(), mappings, settings.options());
    }

    private static void validateOptions(DiscordSettings settings, List<String> lines) {
        String mode = settings.option("discord.commands.players.mode", "both").trim().toLowerCase();
        if (!List.of("both", "text", "slash").contains(mode)) {
            lines.add("FAIL discord.commands.players.mode must be both, text, or slash");
        }
        validateBoolean(settings, lines, "discord.text-commands.enabled");
        String globalChannel = settings.option("discord.notifications.channel-id", "").trim();
        if (!globalChannel.isEmpty() && !isSnowflake(globalChannel)) {
            lines.add("FAIL discord.notifications.channel-id is not a Discord channel ID");
        }
        for (String type : NOTIFICATION_TYPES) {
            String enableKey = "discord.notifications.enable." + type;
            String templateKey = "discord.notifications." + type;
            if (!settings.options().containsKey(enableKey) && !settings.options().containsKey(templateKey)) continue;
            validateBoolean(settings, lines, enableKey);
            if (!Boolean.parseBoolean(settings.option(enableKey, "true"))) continue;
            String target = settings.option("discord.notifications." + type + "-channel-id", globalChannel).trim();
            if (target.isEmpty()) lines.add("WAIT " + type + " notification has no channel ID");
            else if (!isSnowflake(target)) lines.add("FAIL " + type + " notification channel ID is invalid");
            if (settings.option(templateKey, "").isBlank()) {
                lines.add("WAIT " + type + " notification template is blank");
            }
        }
        String role = settings.option("discord.notifications.first-login-role-id", "").trim();
        if (!role.isEmpty() && !isSnowflake(role)) lines.add("FAIL first-login role ID is invalid");
        if (settings.option("discord.minecraft-chat-format", DiscordSettings.DEFAULT_MINECRAFT_CHAT_FORMAT).isBlank()) {
            lines.add("FAIL discord.minecraft-chat-format is blank");
        }
        if (settings.option("discord.external-display-name-format",
                DiscordSettings.DEFAULT_EXTERNAL_DISPLAY_NAME_FORMAT).isBlank()) {
            lines.add("FAIL discord.external-display-name-format is blank");
        }
    }

    private static void validateBoolean(DiscordSettings settings, List<String> lines, String key) {
        String value = settings.option(key, "true").trim().toLowerCase();
        if (!"true".equals(value) && !"false".equals(value)) lines.add("FAIL " + key + " must be true or false");
    }

    private static boolean isSnowflake(String value) {
        return value.matches("[0-9]{5,32}");
    }
}
