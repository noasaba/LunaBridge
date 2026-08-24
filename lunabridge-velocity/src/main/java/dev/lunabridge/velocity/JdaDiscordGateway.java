package dev.lunabridge.velocity;

import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** JDA adapter: channel allowlist, no user-controlled mentions, and bounded asynchronous REST work. */
final class JdaDiscordGateway extends ListenerAdapter implements DiscordGateway {
    private static final int MAX_OUTBOUND = 256;
    private final VelocityNetworkAuthority authority;
    private final VelocitySettings settings;
    private final Logger logger;
    private final Map<String, String> bridgeByDiscordChannel;
    private final AtomicInteger outboundInFlight = new AtomicInteger();
    private final JDA jda;

    private JdaDiscordGateway(VelocityNetworkAuthority authority, VelocitySettings settings, Logger logger, JDA jda) {
        this.authority = authority; this.settings = settings; this.logger = logger; this.jda = jda;
        Map<String, String> reverse = new HashMap<>();
        settings.discordChannels.forEach((bridge, channelId) -> reverse.put(channelId, bridge));
        this.bridgeByDiscordChannel = Map.copyOf(reverse);
    }

    static DiscordGateway start(VelocityNetworkAuthority authority, VelocitySettings settings, Logger logger) {
        if (settings.discordToken.isBlank() || settings.discordToken.equals("PUT_DISCORD_BOT_TOKEN_HERE")) {
            logger.info("LunaBridge Discord gateway disabled: no token configured.");
            return DiscordGateway.disabled();
        }
        try {
            JdaDiscordGateway[] gateway = new JdaDiscordGateway[1];
            JDA jda = JDABuilder.createLight(settings.discordToken)
                    .enableIntents(GatewayIntent.MESSAGE_CONTENT)
                    .addEventListeners(new ListenerAdapter() {
                        @Override public void onMessageReceived(@NotNull MessageReceivedEvent event) { if (gateway[0] != null) gateway[0].handleMessage(event); }
                        @Override public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) { if (gateway[0] != null) gateway[0].handleSlash(event); }
                    })
                    .build();
            JdaDiscordGateway result = new JdaDiscordGateway(authority, settings, logger, jda);
            gateway[0] = result;
            if (playersSlashEnabled(settings)) jda.upsertCommand(Commands.slash("players", "Show online Minecraft players")).queue(
                    ignored -> { }, error -> logger.warn("Could not register LunaBridge /players command"));
            logger.info("LunaBridge Discord gateway started on Velocity as the sole JDA owner.");
            return result;
        } catch (RuntimeException failed) {
            logger.error("LunaBridge Discord gateway did not start; Minecraft/network chat remains available.", failed);
            return DiscordGateway.disabled();
        }
    }

    @Override public void relayMinecraft(BridgeMessage message) {
        String channelId = settings.discordChannels.get(message.bridgeChannel());
        if (channelId == null) return;
        send(channelId, "[" + message.lunaChannelName() + "] " + message.authorName() + ": " + message.content(), false);
    }

    @Override public void notification(String type, Map<String, String> placeholders) {
        if (!Boolean.parseBoolean(settings.properties.getProperty("discord.notifications.enable." + type, "true"))) return;
        String message = settings.properties.getProperty("discord.notifications." + type, "");
        if (message.isBlank()) return;
        String channelId = settings.properties.getProperty("discord.notifications." + type + "-channel-id",
                settings.properties.getProperty("discord.notifications.channel-id", "")).trim();
        if (!channelId.matches("[0-9]{5,32}")) return;
        String roleId = "first-login".equals(type) ? settings.properties.getProperty("discord.notifications.first-login-role-id", "").trim() : "";
        boolean allowRole = roleId.matches("[0-9]{5,32}");
        String text = format(message, placeholders);
        if (allowRole) text = "<@&" + roleId + "> " + text;
        send(channelId, text, allowRole);
    }

    @Override public void close() { jda.shutdown(); }

    private void handleMessage(MessageReceivedEvent event) {
        if (event.getAuthor().isBot() || event.isWebhookMessage()) return;
        String content = event.getMessage().getContentDisplay();
        if (settings.properties.getProperty("discord.commands.players.text-trigger", "!p").equals(content.trim())
                && textPlayersEnabled(settings)) {
            respond(event.getChannel(), playersText());
            return;
        }
        String bridge = bridgeByDiscordChannel.get(event.getChannel().getId());
        if (bridge == null) return; // configured channel IDs are the Discord-to-Minecraft allowlist.
        Instant now = Instant.now();
        String author = event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName();
        try {
            authority.routeDiscordInbound(new BridgeMessage(UUID.randomUUID(), UUID.randomUUID(), BridgeOrigin.DISCORD,
                    bridge, bridge, null, author, content, "discord", now, now.plusSeconds(10)));
        } catch (IllegalArgumentException invalid) {
            logger.warn("Rejected invalid Discord bridge message: {}", invalid.getMessage());
        }
    }

    private void handleSlash(SlashCommandInteractionEvent event) {
        if (!"players".equals(event.getName()) || !playersSlashEnabled(settings)) return;
        event.reply(playersText()).setEphemeral(true).setAllowedMentions(Collections.emptySet()).queue();
    }

    private void respond(MessageChannel channel, String text) {
        if (!reserve()) return;
        channel.sendMessage(text).setAllowedMentions(Collections.emptySet()).mentionRepliedUser(false)
                .queue(ignored -> release(), failed -> { release(); logger.warn("LunaBridge Discord response failed"); });
    }

    private void send(String channelId, String text, boolean allowConfiguredRole) {
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null || !reserve()) return;
        String safe = allowConfiguredRole ? neutralizeExceptLeadingRole(text) : neutralizeMentions(text);
        var action = channel.sendMessage(safe).mentionRepliedUser(false);
        action.setAllowedMentions(allowConfiguredRole ? Collections.singleton(Message.MentionType.ROLE) : Collections.emptySet())
                .queue(ignored -> release(), failed -> { release(); logger.warn("LunaBridge Discord delivery failed"); });
    }

    private boolean reserve() { return outboundInFlight.incrementAndGet() <= MAX_OUTBOUND || releaseAndFalse(); }
    private boolean releaseAndFalse() { outboundInFlight.decrementAndGet(); return false; }
    private void release() { outboundInFlight.decrementAndGet(); }
    private String playersText() {
        var players = authority.onlinePlayerNames();
        return players.isEmpty() ? "No players online." : "Online (" + players.size() + "): " + String.join(", ", players);
    }
    private static boolean textPlayersEnabled(VelocitySettings settings) { return Boolean.parseBoolean(settings.properties.getProperty("discord.text-commands.enabled", "true")) && mode(settings).contains("text"); }
    private static boolean playersSlashEnabled(VelocitySettings settings) { return mode(settings).contains("slash"); }
    private static String mode(VelocitySettings settings) { return settings.properties.getProperty("discord.commands.players.mode", "both").toLowerCase(); }
    private static String format(String template, Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue()).replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }
    private static String neutralizeMentions(String text) { return text.replace("@", "@\u200B"); }
    private static String neutralizeExceptLeadingRole(String text) {
        int end = text.indexOf('>');
        return end >= 0 ? text.substring(0, end + 1) + neutralizeMentions(text.substring(end + 1)) : neutralizeMentions(text);
    }
}
