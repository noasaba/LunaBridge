package dev.lunabridge.discord;

import com.github.ucchyocean.lunachat.api.ChannelDescriptor;
import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.NetworkState;

import java.util.ArrayList;
import java.util.List;

/** Platform-independent setup validation and concise operator diagnostics. */
public final class BridgeAdministration {
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
            if (descriptor.isEmpty()) lines.add("FAIL " + discord + " -> unknown ChannelId " + stableId);
            else if (!descriptor.orElseThrow().acceptsExternalMessages()) {
                lines.add("FAIL " + discord + " -> " + descriptor.orElseThrow().name() + " external messages disabled");
            } else lines.add("OK " + discord + " -> " + descriptor.orElseThrow().name() + " (" + stableId + ")");
        });
        return List.copyOf(lines);
    }
}
