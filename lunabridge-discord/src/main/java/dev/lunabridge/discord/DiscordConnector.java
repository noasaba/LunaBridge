package dev.lunabridge.discord;

import com.github.ucchyocean.lunachat.api.AcceptedMessage;
import com.github.ucchyocean.lunachat.api.ExternalMessageRequest;
import com.github.ucchyocean.lunachat.api.ExternalPublishResult;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.MessageAuthor;
import com.github.ucchyocean.lunachat.api.OriginKind;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Common Discord lifecycle and conversion layer shared by Paper standalone and Velocity. */
public final class DiscordConnector implements AutoCloseable {
    private static final int MAX_OUTBOUND = 256;
    private static final int MAX_PUBLISH_PENDING = 256;
    private static final int MAX_PUBLISH_ATTEMPTS = 5;

    private final LunaChatIntegrationApi lunaChat;
    private final PlayerDirectory players;
    private volatile DiscordSettings settings;
    private final Logger logger;
    private volatile Map<String, List<String>> lunaChannelToDiscordChannels;
    private final MessageReceiptCache receipts = new MessageReceiptCache(4_096, Duration.ofHours(24), Clock.systemUTC());
    private final AtomicInteger outboundInFlight = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object completionMonitor = new Object();
    private final List<PendingOutbound> pendingUntilReady = new ArrayList<>();
    private final ScheduledExecutorService retryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "lunabridge-discord-retry");
        thread.setDaemon(true);
        return thread;
    });
    private final BoundedPublishRetryQueue publishRetries;
    private final JDA jda;
    private volatile boolean ready;

    private record PendingOutbound(String channelId, String text, boolean allowConfiguredRole) { }

    private DiscordConnector(LunaChatIntegrationApi lunaChat, PlayerDirectory players, DiscordSettings settings,
                             Logger logger, JDA jda) {
        this.lunaChat = lunaChat;
        this.players = players;
        this.settings = settings;
        this.logger = logger;
        this.jda = jda;
        this.lunaChannelToDiscordChannels = reverseMappings(settings);
        this.publishRetries = new BoundedPublishRetryQueue(MAX_PUBLISH_PENDING, MAX_PUBLISH_ATTEMPTS,
                this::publishExternalWithDiagnostics, retryExecutor, Clock.systemUTC());
    }

    public static DiscordConnector start(LunaChatIntegrationApi lunaChat, PlayerDirectory players,
                                          DiscordSettings settings, Logger logger) {
        if (settings.token().isBlank() || settings.token().equals("PUT_DISCORD_BOT_TOKEN_HERE")) {
            logger.info("LunaBridge Discord gateway disabled: no token configured.");
            return disabled(lunaChat, players, settings, logger);
        }
        JDA jda = null;
        try {
            jda = JDABuilder.createDefault(settings.token())
                    .setEnableShutdownHook(false)
                    .enableIntents(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT)
                    .build();
            DiscordConnector result = new DiscordConnector(lunaChat, players, settings, logger, jda);
            DiscordConnector listenerTarget = result;
            jda.addEventListener(new ListenerAdapter() {
                @Override public void onReady(@NotNull ReadyEvent event) { listenerTarget.markReady(); }
                @Override public void onMessageReceived(@NotNull MessageReceivedEvent event) { listenerTarget.handleMessage(event); }
                @Override public void onSlashCommandInteraction(@NotNull SlashCommandInteractionEvent event) { listenerTarget.handleSlash(event); }
            });
            if (jda.getStatus() == JDA.Status.CONNECTED) result.markReady();
            synchronizePlayersSlashCommand(jda, settings, logger);
            logger.info("LunaBridge Discord gateway started as the platform-independent connector.");
            return result;
        } catch (RuntimeException failed) {
            if (jda != null) try { jda.shutdownNow(); } catch (RuntimeException cleanupFailed) { failed.addSuppressed(cleanupFailed); }
            logger.error("LunaBridge Discord gateway did not start; LunaChat remains available.", failed);
            return disabled(lunaChat, players, settings, logger);
        }
    }

    private static DiscordConnector disabled(LunaChatIntegrationApi api, PlayerDirectory players,
                                             DiscordSettings settings, Logger logger) {
        return new DiscordConnector(api, players, settings, logger, null);
    }

    public void reconfigure(DiscordSettings updated) {
        if (!settings.token().equals(updated.token())) {
            throw new IllegalArgumentException("Discord token changes require a plugin restart");
        }
        boolean slashModeChanged = playersSlashEnabled(settings) != playersSlashEnabled(updated);
        settings = updated;
        lunaChannelToDiscordChannels = reverseMappings(updated);
        if (slashModeChanged && jda != null) synchronizePlayersSlashCommand(jda, updated, logger);
    }

    public boolean hasGateway() { return jda != null && !closed.get(); }

    public boolean isReady() { return ready && !closed.get(); }

    public boolean sendSetupTest(String discordChannelId, String channelName) {
        if (!isReady()) return false;
        MessageChannel channel = jda.getChannelById(MessageChannel.class, discordChannelId);
        if (channel == null || !channel.canTalk()) return false;
        return send("✅ LunaBridge mapped this Discord channel to LunaChat "
                + DiscordText.suppressMentions(channelName) + ".", discordChannelId, false);
    }

    public void relayMinecraft(AcceptedMessage message) {
        if (closed.get() || message.origin().kind() != OriginKind.MINECRAFT) return;
        List<String> channelIds = lunaChannelToDiscordChannels.get(message.channelId().value());
        if (channelIds == null || channelIds.isEmpty() || !receipts.markIfNew(message.messageId())) return;
        String template = settings.option("discord.minecraft-chat-format",
                DiscordSettings.DEFAULT_MINECRAFT_CHAT_FORMAT);
        String text = DiscordText.suppressMentions(minecraftRelayText(message, template));
        channelIds.forEach(channelId -> send(text, channelId, false));
    }

    static String minecraftRelayText(AcceptedMessage message, String template) {
        String channelName = DiscordText.stripMinecraftLegacyFormatting(message.channelName());
        String author = DiscordText.stripMinecraftLegacyFormatting(authorName(message));
        MinecraftChatParts content = splitMinecraftContent(message.content());
        return format(template, Map.of(
                "channel", channelName,
                "username", author,
                "message", content.message(),
                "japanized", content.japanized()));
    }

    private static MinecraftChatParts splitMinecraftContent(String content) {
        int marker = content.lastIndexOf(" §6(");
        if (marker >= 0 && content.endsWith(")")) {
            String message = DiscordText.stripMinecraftLegacyFormatting(content.substring(0, marker));
            String japanized = DiscordText.stripMinecraftLegacyFormatting(content.substring(marker));
            return new MinecraftChatParts(message, japanized);
        }
        return new MinecraftChatParts(DiscordText.stripMinecraftLegacyFormatting(content), "");
    }

    private record MinecraftChatParts(String message, String japanized) { }

    public void notification(String type, Map<String, String> placeholders) {
        if (closed.get() || !Boolean.parseBoolean(settings.option("discord.notifications.enable." + type, "true"))) return;
        String template = settings.option("discord.notifications." + type, "");
        if (template.isBlank()) return;
        String channelId = settings.option("discord.notifications." + type + "-channel-id",
                settings.option("discord.notifications.channel-id", "")).trim();
        if (!channelId.matches("[0-9]{5,32}")) return;
        String roleId = "first-login".equals(type)
                ? settings.option("discord.notifications.first-login-role-id", "").trim() : "";
        boolean allowRole = roleId.matches("[0-9]{5,32}");
        String text = format(template, placeholders);
        if (allowRole) text = "<@&" + roleId + "> " + text;
        send(allowRole ? DiscordText.suppressMentionsExceptLeadingRole(text) : DiscordText.suppressMentions(text),
                channelId, allowRole);
    }

    public void finalNotification(String type, Map<String, String> placeholders) {
        notification(type, placeholders);
        awaitOutbound(Duration.ofSeconds(3));
        close();
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        pendingUntilReady().forEach(ignored -> release());
        publishRetries.close();
        if (jda != null) jda.shutdown();
    }

    private void handleMessage(MessageReceivedEvent event) {
        if (closed.get() || event.getAuthor().isBot() || event.isWebhookMessage()) return;
        String lunaChannelId = settings.discordChannelToLunaChatChannelId().get(event.getChannel().getId());
        if (lunaChannelId == null) return;
        String messageContent = event.getMessage().getContentDisplay();
        if (settings.option("discord.commands.players.text-trigger", "!p").equals(messageContent.trim())
                && textPlayersEnabled(settings)) {
            respond(event.getChannel(), playersText());
            return;
        }
        String content = externalContent(messageContent, event.getMessage().getAttachments().stream()
                .filter(Message.Attachment::isImage)
                .map(Message.Attachment::getUrl)
                .toList());
        String effectiveName = event.getMember() == null ? event.getAuthor().getName() : event.getMember().getEffectiveName();
        String displayName = externalDisplayName(settings.option("discord.external-display-name-format",
                DiscordSettings.DEFAULT_EXTERNAL_DISPLAY_NAME_FORMAT), effectiveName);
        try {
            ExternalMessageRequest request = new ExternalMessageRequest(
                    new com.github.ucchyocean.lunachat.api.ChannelId(lunaChannelId),
                    new com.github.ucchyocean.lunachat.api.ExternalMessageIdentity("lunabridge:discord", event.getMessageId()),
                    new MessageAuthor.External("lunabridge:discord", event.getAuthor().getId(), displayName),
                    content, Instant.now(), Duration.ofMinutes(5));
            if (!publishRetries.submit(request)) logger.warn("LunaBridge Discord publish queue is full or closing");
        } catch (IllegalArgumentException invalid) {
            logger.warn("Rejected invalid Discord bridge message: {}", invalid.getMessage());
        }
    }

    private CompletionStage<ExternalPublishResult> publishExternalWithDiagnostics(ExternalMessageRequest request) {
        logger.info("LunaBridge external publish request: {}", externalPublishRequestDiagnostic(request));
        CompletionStage<ExternalPublishResult> stage;
        try {
            stage = lunaChat.messages().publishExternal(request);
        } catch (RuntimeException failure) {
            logger.warn("LunaBridge external publish threw before admission: {}", externalPublishRequestDiagnostic(request), failure);
            throw failure;
        }
        stage.whenComplete((result, failure) -> {
            if (failure != null) {
                logger.warn("LunaBridge external publish failed before admission result: {}",
                        externalPublishRequestDiagnostic(request), failure);
            } else if (result == null) {
                logger.warn("LunaBridge external publish returned no admission result: {}",
                        externalPublishRequestDiagnostic(request));
            } else {
                logger.info("LunaBridge external publish admission result: {}",
                        externalPublishResultDiagnostic(request, result));
            }
        });
        return stage;
    }

    static String externalPublishRequestDiagnostic(ExternalMessageRequest request) {
        return "channelId=" + request.channelId().value()
                + " identity=" + request.identity().namespace() + ":" + request.identity().value();
    }

    static String externalDisplayName(String format, String effectiveName) {
        String safeName = stripExternalDisplayName(effectiveName);
        if (safeName.isBlank()) safeName = "unknown";
        String safeFormat = stripExternalDisplayName(format);
        String displayName = safeFormat.replace("{username}", safeName).trim();
        return displayName.isBlank() ? safeName : displayName;
    }

    static String externalContent(String messageContent, List<String> imageUrls) {
        String urls = imageUrls.stream().filter(url -> !url.isBlank()).distinct()
                .collect(java.util.stream.Collectors.joining(" "));
        if (urls.isEmpty()) return DiscordText.fit(messageContent);
        return DiscordText.fit(messageContent.isBlank() ? urls : messageContent + " " + urls);
    }

    private static String stripExternalDisplayName(String text) {
        StringBuilder sanitized = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\u00a7') {
                if (index + 1 < text.length()) index++;
                continue;
            }
            if (character == '\r' || character == '\n' || Character.isISOControl(character)) continue;
            sanitized.append(character);
        }
        return sanitized.toString();
    }

    static String externalPublishResultDiagnostic(ExternalMessageRequest request, ExternalPublishResult result) {
        return externalPublishRequestDiagnostic(request)
                + " admissionStatus=" + result.status()
                + " logicalMessageId=" + (result.messageId() == null ? "none" : result.messageId())
                + " retryable=" + result.retryable()
                + " diagnostic=" + result.diagnosticCode()
                + " clientDeliveryConfirmed=false";
    }

    private void handleSlash(SlashCommandInteractionEvent event) {
        if (!"players".equals(event.getName())) return;
        if (!playersSlashEnabled(settings)) {
            respondSlash(event, "The LunaBridge /players command is disabled.");
            return;
        }
        if (!isAllowedCommandChannel(settings, event.getChannel().getId())) {
            respondSlash(event, "LunaBridge commands are not enabled in this channel.");
            return;
        }
        respondSlash(event, playersText());
    }

    private void respondSlash(SlashCommandInteractionEvent event, String text) {
        if (!reserve()) return;
        try {
            event.reply(DiscordText.fit(DiscordText.suppressMentions(text))).setEphemeral(true)
                    .setAllowedMentions(Collections.emptySet())
                    .queue(ignored -> release(), failed -> { release(); logger.warn("LunaBridge Discord slash response failed"); });
        } catch (RuntimeException unavailable) { release(); logger.warn("LunaBridge Discord slash response unavailable"); }
    }

    private void respond(MessageChannel channel, String text) {
        if (!reserve()) return;
        try {
            channel.sendMessage(DiscordText.fit(DiscordText.suppressMentions(text))).setAllowedMentions(Collections.emptySet())
                    .mentionRepliedUser(false).queue(ignored -> release(), failed -> { release(); logger.warn("LunaBridge Discord response failed"); });
        } catch (RuntimeException unavailable) { release(); logger.warn("LunaBridge Discord response unavailable"); }
    }

    private boolean send(String text, String channelId, boolean allowConfiguredRole) {
        if (jda == null || !reserve()) return false;
        String safe = DiscordText.fit(text);
        synchronized (pendingUntilReady) {
            if (closed.get()) { release(); return false; }
            if (!ready) {
                pendingUntilReady.add(new PendingOutbound(channelId, safe, allowConfiguredRole));
                return true;
            }
        }
        dispatch(channelId, safe, allowConfiguredRole);
        return true;
    }

    private void markReady() {
        List<PendingOutbound> pending;
        synchronized (pendingUntilReady) {
            if (ready || closed.get()) return;
            ready = true;
            pending = new ArrayList<>(pendingUntilReady);
            pendingUntilReady.clear();
        }
        logger.info("LunaBridge Discord gateway ready; flushing {} queued delivery attempt(s).", pending.size());
        pending.forEach(outbound -> dispatch(outbound.channelId(), outbound.text(), outbound.allowConfiguredRole()));
    }

    private List<PendingOutbound> pendingUntilReady() {
        synchronized (pendingUntilReady) {
            List<PendingOutbound> pending = new ArrayList<>(pendingUntilReady);
            pendingUntilReady.clear();
            return pending;
        }
    }

    private void dispatch(String channelId, String text, boolean allowConfiguredRole) {
        if (closed.get() || jda == null) { release(); return; }
        MessageChannel channel = jda.getChannelById(MessageChannel.class, channelId);
        if (channel == null) { release(); logger.warn("LunaBridge Discord channel {} is unavailable", channelId); return; }
        try {
            channel.sendMessage(text).mentionRepliedUser(false)
                    .setAllowedMentions(allowConfiguredRole ? Set.of(Message.MentionType.ROLE) : Collections.emptySet())
                    .queue(ignored -> release(), failed -> { release(); logger.warn("LunaBridge Discord delivery failed"); });
        } catch (RuntimeException unavailable) { release(); logger.warn("LunaBridge Discord delivery unavailable"); }
    }

    private boolean reserve() {
        if (jda == null || closed.get()) return false;
        int reserved = outboundInFlight.incrementAndGet();
        if (reserved > MAX_OUTBOUND || closed.get()) { release(); return false; }
        return true;
    }

    private void release() {
        outboundInFlight.decrementAndGet();
        synchronized (completionMonitor) { completionMonitor.notifyAll(); }
    }

    private void awaitOutbound(Duration maximum) {
        long deadline = System.nanoTime() + maximum.toNanos();
        synchronized (completionMonitor) {
            while (outboundInFlight.get() > 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) break;
                try { completionMonitor.wait(Math.max(1, Math.min(Duration.ofNanos(remaining).toMillis(), 250))); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
    }

    private String playersText() {
        return playersText(players);
    }

    static String playersText(PlayerDirectory players) {
        List<PlayerDirectory.PlayerGroup> groups = players.onlinePlayersByServer().stream()
                .filter(group -> !group.playerNames().isEmpty())
                .sorted(java.util.Comparator.comparing(PlayerDirectory.PlayerGroup::serverName))
                .toList();
        if (groups.isEmpty()) return "現在ログイン中のプレイヤーはいません";
        String body = groups.stream()
                .map(group -> group.serverName() + " (" + group.playerNames().size() + "): "
                        + String.join(" ", group.playerNames().stream().sorted().toList()))
                .collect(java.util.stream.Collectors.joining("\n"));
        return "ログイン中のプレイヤー\n```\n" + body + "\n```";
    }

    public static boolean textPlayersEnabled(DiscordSettings settings) {
        String mode = settings.option("discord.commands.players.mode", "both").toLowerCase();
        return Boolean.parseBoolean(settings.option("discord.text-commands.enabled", "true"))
                && ("text".equals(mode) || "both".equals(mode));
    }

    public static boolean playersSlashEnabled(DiscordSettings settings) {
        String mode = settings.option("discord.commands.players.mode", "both").toLowerCase();
        return "slash".equals(mode) || "both".equals(mode);
    }

    public static boolean isAllowedCommandChannel(DiscordSettings settings, String channelId) {
        return settings.discordChannelToLunaChatChannelId().containsKey(channelId);
    }

    private static String format(String template, Map<String, String> placeholders) {
        String result = template;
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue())
                    .replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    private static String authorName(AcceptedMessage message) {
        MessageAuthor author = message.author();
        if (author instanceof MessageAuthor.Player player) return player.displayName();
        if (author instanceof MessageAuthor.External external) return external.displayName();
        if (author instanceof MessageAuthor.System system) return system.name();
        return "Unknown";
    }

    static Map<String, List<String>> reverseMappings(DiscordSettings settings) {
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        settings.discordChannelToLunaChatChannelId().forEach((discord, luna) ->
                reverse.computeIfAbsent(luna, ignored -> new ArrayList<>()).add(discord));
        reverse.replaceAll((ignored, discordChannels) -> List.copyOf(discordChannels));
        return Map.copyOf(reverse);
    }

    private static void synchronizePlayersSlashCommand(JDA jda, DiscordSettings settings, Logger logger) {
        if (playersSlashEnabled(settings)) {
            jda.upsertCommand(Commands.slash("players", "Show online Minecraft players"))
                    .queue(ignored -> { }, error -> logger.warn("Could not register LunaBridge /players command"));
            return;
        }
        jda.retrieveCommands().queue(commands -> commands.stream()
                        .filter(command -> "players".equals(command.getName()))
                        .forEach(command -> jda.deleteCommandById(command.getId()).queue(
                                ignored -> { }, error -> logger.warn("Could not remove stale LunaBridge /players command"))),
                error -> logger.warn("Could not inspect Discord commands for stale LunaBridge /players command"));
    }
}
