package dev.lunabridge.velocity;

import com.github.ucchyocean.lunachat.api.LunaChatApiProvider;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.RuntimeRole;
import com.github.ucchyocean.lunachat.api.Subscription;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import dev.lunabridge.discord.AuthorityValidation;
import dev.lunabridge.discord.BridgeAdministration;
import dev.lunabridge.discord.DiscordConnector;
import dev.lunabridge.discord.PlayerDirectory;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.List;

/** Velocity platform adapter; LunaChat owns all Minecraft network authority and transport. */
@Plugin(id = "lunabridge-velocity", name = "LunaBridge Velocity", version = LunaBridgeBuildVersion.VERSION,
        authors = {"LunaBridge"}, dependencies = {
        @Dependency(id = "lunachat", optional = false), @Dependency(id = "svsync", optional = true)})
public final class LunaBridgeVelocityPlugin {
    static final String ADMIN_PERMISSION = "lunabridge.admin";
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final Set<UUID> connected = ConcurrentHashMap.newKeySet();
    private final Map<UUID, PlayerLocation> playerLocations = new ConcurrentHashMap<>();
    private DiscordConnector discord;
    private Subscription subscription;
    private SeenPlayerStore seenPlayers;
    private VelocitySettings settings;
    private LunaChatIntegrationApi api;
    private SVSyncVisibilityProvider svsync;

    private record PlayerLocation(String playerName, String serverName) { }

    @Inject public LunaBridgeVelocityPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe public void initialize(ProxyInitializeEvent event) {
        try {
            settings = VelocitySettings.load(dataDirectory);
            Object instance = proxy.getPluginManager().getPlugin("lunachat")
                    .flatMap(container -> container.getInstance()).orElse(null);
            if (!(instance instanceof LunaChatApiProvider provider)) {
                throw new IllegalStateException("LunaChat-Velocity provider is unavailable");
            }
            api = provider.current().orElseThrow(
                    () -> new IllegalStateException("LunaChat Integration API v1 is not currently available"));
            AuthorityValidation.require(api, RuntimeRole.NETWORK_AUTHORITY);
            svsync = SVSyncVisibilityProvider.find(proxy, logger).orElse(null);
            if (svsync != null) logger.info("SVSync public presence filtering enabled.");
            proxy.getCommandManager().register(proxy.getCommandManager().metaBuilder("lunabridge")
                    .plugin(this).build(), new AdministrationCommand());
            for (String channelId : settings.discord.discordChannelToLunaChatChannelId().values()) {
                api.channels().find(new com.github.ucchyocean.lunachat.api.ChannelId(channelId)).orElseThrow(
                        () -> new IllegalStateException("Unknown LunaChat ChannelId " + channelId));
            }
            seenPlayers = new SeenPlayerStore(dataDirectory);
            captureConnectedPlayers();
            PlayerDirectory players = new PlayerDirectory() {
                @Override public List<String> onlinePlayerNames() { return publicPlayerNames(); }
                @Override public List<PlayerGroup> onlinePlayersByServer() { return publicPlayersByServer(); }
            };
            discord = DiscordConnector.start(api, players, settings.discord, logger);
            subscription = api.messages().observeAcceptedMessages(discord::relayMinecraft);
            discord.notification("startup", Map.of("online", Integer.toString(publicPlayerCount()), "max", "?"));
            logger.info("LunaBridge enabled as a Discord-only LunaChat API consumer.");
        } catch (IOException | RuntimeException failure) {
            closeBridge();
            logger.error("LunaBridge did not start; LunaChat remains untouched.", failure);
        }
    }

    @Subscribe public void connected(ServerConnectedEvent event) {
        if (settings == null || discord == null) return;
        String to = event.getServer().getServerInfo().getName();
        playerLocations.put(event.getPlayer().getUniqueId(), new PlayerLocation(event.getPlayer().getUsername(), to));
        Map<String, String> values = values(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), "", to);
        if (event.getPreviousServer().isEmpty()) {
            connected.add(event.getPlayer().getUniqueId());
            notifyJoinAfterSVSyncState(event.getPlayer().getUniqueId(), values);
        } else {
            String from = event.getPreviousServer().orElseThrow().getServerInfo().getName();
            if (!from.equals(to) && isPublic(event.getPlayer().getUniqueId())) {
                discord.notification("server-switch", values(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), from, to));
            }
        }
    }

    @Subscribe public void disconnected(DisconnectEvent event) {
        playerLocations.remove(event.getPlayer().getUniqueId());
        if (discord != null && connected.remove(event.getPlayer().getUniqueId()) && isPublic(event.getPlayer().getUniqueId())) {
            discord.notification("quit", values(event.getPlayer().getUsername(), event.getPlayer().getUniqueId(), "", ""));
        }
    }

    @Subscribe public void shutdown(ProxyShutdownEvent event) {
        if (discord != null) discord.finalNotification("shutdown", Map.of("online", Integer.toString(publicPlayerCount()), "max", "?"));
        cleanup();
    }

    private void cleanup() {
        proxy.getCommandManager().unregister("lunabridge");
        closeBridge();
        seenPlayers = null;
        settings = null;
        api = null;
        svsync = null;
        connected.clear();
        playerLocations.clear();
    }

    private void closeBridge() {
        if (subscription != null) try { subscription.close(); } catch (RuntimeException failure) { logger.warn("Could not close LunaChat subscription", failure); }
        subscription = null;
        if (discord != null) try { discord.close(); } catch (RuntimeException failure) { logger.warn("Could not close Discord connector", failure); }
        discord = null;
    }

    private static Map<String, String> values(String player, UUID uuid, String from, String server) {
        return Map.of("player", player, "uuid", uuid.toString(), "from", from, "server", server);
    }

    private void notifyJoinAfterSVSyncState(UUID playerId, Map<String, String> values) {
        proxy.getScheduler().buildTask(this, () -> {
            if (discord == null || !connected.contains(playerId) || !isPublic(playerId)) return;
            try {
                discord.notification(seenPlayers != null && seenPlayers.markFirst(playerId) ? "first-login" : "login", values);
            } catch (IOException failure) {
                logger.error("Could not persist first-login state", failure);
            }
        }).delay(400, TimeUnit.MILLISECONDS).schedule();
    }

    private boolean isPublic(UUID playerId) {
        return svsync == null || svsync.isPublic(playerId);
    }

    private List<String> publicPlayerNames() {
        return playerLocations.entrySet().stream()
                .filter(entry -> isPublic(entry.getKey()))
                .map(entry -> entry.getValue().playerName()).toList();
    }

    private int publicPlayerCount() {
        return publicPlayerNames().size();
    }

    private List<PlayerDirectory.PlayerGroup> publicPlayersByServer() {
        Map<String, List<String>> grouped = new TreeMap<>();
        playerLocations.forEach((playerId, location) -> {
            if (isPublic(playerId)) {
                grouped.computeIfAbsent(location.serverName(), ignored -> new ArrayList<>()).add(location.playerName());
            }
        });
        return grouped.entrySet().stream()
                .map(entry -> new PlayerDirectory.PlayerGroup(entry.getKey(), entry.getValue())).toList();
    }

    private void captureConnectedPlayers() {
        proxy.getAllPlayers().forEach(player -> player.getCurrentServer().ifPresent(server ->
                playerLocations.put(player.getUniqueId(), new PlayerLocation(player.getUsername(),
                        server.getServerInfo().getName()))));
    }

    private final class AdministrationCommand implements SimpleCommand {
        @Override public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            boolean console = source instanceof ConsoleCommandSource;
            if (!isAdministrationAuthorized(console, !console && source.hasPermission(ADMIN_PERMISSION))) {
                source.sendPlainMessage("You do not have permission to administer LunaBridge.");
                return;
            }
            String[] arguments = invocation.arguments();
            if (arguments.length == 1 && "doctor".equalsIgnoreCase(arguments[0])) {
                BridgeAdministration.doctor(api, settings.discord, discord != null && discord.hasGateway(),
                        discord != null && discord.isReady()).forEach(invocation.source()::sendPlainMessage);
                return;
            }
            if (arguments.length == 3 && "setup".equalsIgnoreCase(arguments[0])) {
                try {
                    var channel = BridgeAdministration.resolveSetup(api, arguments[1], arguments[2]);
                    VelocitySettings.saveMapping(dataDirectory, arguments[1], channel.id().value());
                    VelocitySettings updated = VelocitySettings.load(dataDirectory);
                    settings = updated;
                    invocation.source().sendPlainMessage("OK mapped Discord " + arguments[1] + " -> "
                            + channel.name() + " (" + channel.id().value() + ")");
                    if (discord == null) invocation.source().sendPlainMessage("WAIT mapping saved; restart the proxy to recover the bridge");
                    else {
                        discord.reconfigure(updated.discord);
                        invocation.source().sendPlainMessage(discord.sendSetupTest(arguments[1], channel.name())
                                ? "OK Discord setup test queued" : "FAIL Discord gateway unavailable; mapping was saved");
                    }
                } catch (IOException | RuntimeException failure) {
                    invocation.source().sendPlainMessage("FAIL " + failure.getMessage());
                }
                return;
            }
            invocation.source().sendPlainMessage("Usage: lunabridge doctor | lunabridge setup <discord-channel-id> <lunachat-name-or-alias>");
        }

        @Override public List<String> suggest(Invocation invocation) {
            if (invocation.arguments().length <= 1) return List.of("doctor", "setup");
            return List.of();
        }
    }

    static boolean isAdministrationAuthorized(boolean console, boolean permissionGranted) {
        return console || permissionGranted;
    }
}
