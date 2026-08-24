package dev.lunabridge.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.scheduler.ScheduledTask;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Velocity owns all network state and the sole Discord client in proxy topology. */
@Plugin(id = "lunabridge-velocity", name = "LunaBridge Velocity", version = "0.1.0-SNAPSHOT")
public final class LunaBridgeVelocityPlugin {
    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from("lunabridge:network");
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> connectedPlayers = ConcurrentHashMap.newKeySet();
    private VelocityNetworkAuthority authority;
    private DiscordGateway discord = DiscordGateway.disabled();
    private SeenPlayerStore seenPlayers;
    private VelocitySettings settings;
    private ScheduledTask tickTask;
    private boolean channelRegistered;

    @Inject
    public LunaBridgeVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy; this.logger = logger; this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            settings = VelocitySettings.load(dataDirectory);
            authority = new VelocityNetworkAuthority(proxy, logger, settings, EpochStore.next(dataDirectory));
            seenPlayers = new SeenPlayerStore(dataDirectory);
            discord = JdaDiscordGateway.start(authority, settings, logger);
            authority.attachDiscord(discord);
            proxy.getChannelRegistrar().register(CHANNEL);
            channelRegistered = true;
            tickTask = proxy.getScheduler().buildTask(this, authority::tick).repeat(Duration.ofSeconds(1)).schedule();
            discord.notification("startup", Map.of("online", Integer.toString(proxy.getPlayerCount()), "max", "?"));
            logger.info("LunaBridge Velocity enabled as network authority.");
        } catch (IOException | RuntimeException failure) {
            cleanup();
            logger.error("LunaBridge Velocity did not start; no insecure fallback will be enabled.", failure);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) {
            logger.warn("LunaBridge dropped a plugin message from a non-backend source.");
            return;
        }
        VelocityNetworkAuthority current = authority;
        if (current != null) current.receive(connection, event.getData());
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (authority == null) return;
        String to = event.getServer().getServerInfo().getName();
        Map<String, String> placeholders = placeholders(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), "", to);
        if (event.getPreviousServer().isEmpty()) {
            connectedPlayers.add(event.getPlayer().getUniqueId());
            if (seenPlayers != null && seenPlayers.markFirst(event.getPlayer().getUniqueId()) && notificationEnabled("first-login")) {
                discord.notification("first-login", placeholders);
            } else if (notificationEnabled("login")) {
                discord.notification("login", placeholders);
            } else {
                discord.notification("join", placeholders);
            }
            return;
        }
        String from = event.getPreviousServer().orElseThrow().getServerInfo().getName();
        if (!from.equals(to)) discord.notification("server-switch", placeholders(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), from, to));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        if (connectedPlayers.remove(event.getPlayer().getUniqueId())) {
            discord.notification("quit", placeholders(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), "", ""));
        }
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        discord.notification("shutdown", Map.of("online", Integer.toString(proxy.getPlayerCount()), "max", "?"));
        cleanup();
    }

    private void cleanup() {
        if (tickTask != null) try { tickTask.cancel(); }
        catch (RuntimeException failed) { logger.warn("LunaBridge scheduler cleanup failed", failed); }
        tickTask = null;
        try { discord.close(); }
        catch (RuntimeException failed) { logger.warn("LunaBridge Discord cleanup failed", failed); }
        if (authority != null) try { authority.close(); }
        catch (RuntimeException failed) { logger.warn("LunaBridge network cleanup failed", failed); }
        if (channelRegistered) try { proxy.getChannelRegistrar().unregister(CHANNEL); }
        catch (RuntimeException failed) { logger.warn("LunaBridge channel cleanup failed", failed); }
        channelRegistered = false;
        authority = null;
        discord = DiscordGateway.disabled();
        seenPlayers = null;
        settings = null;
    }

    private Map<String, String> placeholders(String player, UUID uuid, String from, String to) {
        return Map.of("player", player, "uuid", uuid.toString(), "server", to, "fromServer", from, "toServer", to,
                "online", Integer.toString(proxy.getPlayerCount()), "max", "?");
    }
    private boolean notificationEnabled(String type) {
        return settings != null && Boolean.parseBoolean(settings.properties.getProperty("discord.notifications.enable." + type, "true"));
    }
}
