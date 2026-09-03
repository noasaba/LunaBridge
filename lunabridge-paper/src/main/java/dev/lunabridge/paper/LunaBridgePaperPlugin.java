package dev.lunabridge.paper;

import com.github.ucchyocean.lunachat.api.ChannelId;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.RuntimeRole;
import com.github.ucchyocean.lunachat.api.Subscription;
import dev.lunabridge.discord.AuthorityValidation;
import dev.lunabridge.discord.BridgeAdministration;
import dev.lunabridge.discord.DiscordConnector;
import dev.lunabridge.discord.PlayerDirectory;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.LinkedHashMap;

/** Paper standalone adapter; it never observes legacy chat events or owns Minecraft transport. */
public final class LunaBridgePaperPlugin extends JavaPlugin implements Listener {
    private DiscordConnector discord;
    private Subscription subscription;
    private LunaChatIntegrationApi api;
    private PaperSettings settings;
    private final PublicPresenceSnapshot publiclyOnline = new PublicPresenceSnapshot();
    private PublicVisibilityProvider visibilityProvider = new DefaultVisibilityProvider();
    private Listener vanishListener;

    @Override public void onEnable() {
        try {
            var command = getCommand("lunabridge");
            if (command == null) throw new IllegalStateException("lunabridge command metadata missing");
            command.setExecutor(this::onAdministrationCommand);
            settings = PaperSettings.load(this);
            RegisteredServiceProvider<LunaChatIntegrationApi> registration =
                    Bukkit.getServicesManager().getRegistration(LunaChatIntegrationApi.class);
            api = registration == null ? null : registration.getProvider();
            AuthorityValidation.require(api, RuntimeRole.STANDALONE_AUTHORITY);
            for (String channelId : settings.discord.discordChannelToLunaChatChannelId().values()) {
                api.channels().find(new ChannelId(channelId)).orElseThrow(
                        () -> new IllegalStateException("Unknown LunaChat ChannelId " + channelId));
            }
            refreshVisibilityProvider();
            PlayerDirectory players = this::publicPlayerNames;
            discord = DiscordConnector.start(api, players, settings.discord, getSLF4JLogger());
            subscription = api.messages().observeAcceptedMessages(discord::relayMinecraft);
            Bukkit.getPluginManager().registerEvents(this, this);
            discord.notification("startup", Map.of("online", Integer.toString(publiclyOnline.size()), "max", "?"));
            getLogger().info("LunaBridge Paper standalone enabled as a Discord-only LunaChat API consumer.");
        } catch (RuntimeException | LinkageError failure) {
            closeBridge();
            closeVanishListener();
            publiclyOnline.clear();
            getLogger().severe("LunaBridge Paper standalone refused to start before Discord connection: " + failure.getMessage());
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!isPublic(event.getPlayer()) || !publiclyOnline.markPublic(event.getPlayer().getUniqueId(), event.getPlayer().getName())) return;
        if (discord != null) discord.notification("join", Map.of("player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString(), "server", Bukkit.getServer().getName()));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        String playerName = publiclyOnline.markHidden(event.getPlayer().getUniqueId());
        if (playerName == null) return;
        if (discord != null) discord.notification("quit", Map.of("player", playerName,
                "uuid", event.getPlayer().getUniqueId().toString(), "server", Bukkit.getServer().getName()));
    }

    @EventHandler public void onVanishPluginEnabled(PluginEnableEvent event) {
        if (isVanishPlugin(event.getPlugin().getName())) refreshVisibilityProvider();
    }

    @EventHandler public void onVanishPluginDisabled(PluginDisableEvent event) {
        if (!isVanishPlugin(event.getPlugin().getName())) return;
        failClosed("Vanish plugin was disabled", null);
    }

    private void refreshVisibilityProvider() {
        closeVanishListener();
        if (!hasVanishPluginInstalled()) {
            visibilityProvider = new DefaultVisibilityProvider();
            rebuildPublicSnapshot();
            return;
        }
        if (!hasEnabledVanishPlugin()) {
            failClosed("Vanish plugin is installed but not enabled", null);
            return;
        }
        Listener listener = null;
        try {
            PublicVisibilityProvider provider = new SuperVanishVisibilityProvider();
            listener = new Listener() { };
            registerVanishEvent("de.myzelyam.api.vanish.PostPlayerHideEvent", false, listener);
            registerVanishEvent("de.myzelyam.api.vanish.PostPlayerShowEvent", true, listener);
            visibilityProvider = provider;
            vanishListener = listener;
            rebuildPublicSnapshot();
            getLogger().info("SuperVanish public presence integration enabled.");
        } catch (RuntimeException | LinkageError failure) {
            if (listener != null) HandlerList.unregisterAll(listener);
            failClosed("Vanish plugin integration failed; Discord public presence is fail-closed", failure);
        }
    }

    private boolean hasVanishPluginInstalled() {
        return Bukkit.getPluginManager().getPlugin("SuperVanish") != null
                || Bukkit.getPluginManager().getPlugin("PremiumVanish") != null;
    }

    private boolean hasEnabledVanishPlugin() {
        return Bukkit.getPluginManager().isPluginEnabled("SuperVanish")
                || Bukkit.getPluginManager().isPluginEnabled("PremiumVanish");
    }

    private static boolean isVanishPlugin(String pluginName) {
        return "SuperVanish".equals(pluginName) || "PremiumVanish".equals(pluginName);
    }

    private void rebuildPublicSnapshot() {
        Map<UUID, String> snapshot = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isPublic(player)) snapshot.put(player.getUniqueId(), player.getName());
            if (visibilityProvider instanceof FailClosedVisibilityProvider) {
                publiclyOnline.clear();
                return;
            }
        }
        publiclyOnline.replace(snapshot);
    }

    private boolean isPublic(Player player) {
        try {
            return visibilityProvider.isPublic(player);
        } catch (RuntimeException | LinkageError failure) {
            failClosed("Vanish visibility lookup failed; Discord public presence is fail-closed", failure);
            return false;
        }
    }

    private void failClosed(String message, Throwable failure) {
        closeVanishListener();
        visibilityProvider = new FailClosedVisibilityProvider();
        publiclyOnline.clear();
        if (failure == null) getLogger().severe(message);
        else getLogger().log(java.util.logging.Level.SEVERE, message, failure);
    }

    private void closeVanishListener() {
        if (vanishListener != null) HandlerList.unregisterAll(vanishListener);
        vanishListener = null;
    }

    @SuppressWarnings("unchecked")
    private void registerVanishEvent(String className, boolean shown, Listener listener) {
        try {
            Class<? extends Event> eventType = (Class<? extends Event>) Class.forName(className);
            EventExecutor executor = (ignored, event) -> handleVanishEvent(event, shown);
            Bukkit.getPluginManager().registerEvent(eventType, listener, EventPriority.MONITOR, executor, this, true);
        } catch (ClassNotFoundException | LinkageError missingEvent) {
            throw new IllegalStateException("Missing SuperVanish event " + className, missingEvent);
        }
    }

    private void handleVanishEvent(Event event, boolean shown) {
        try {
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            if (!player.isOnline()) return;
            if (shown && isPublic(player)) publiclyOnline.markPublic(player.getUniqueId(), player.getName());
            else publiclyOnline.markHidden(player.getUniqueId());
        } catch (ReflectiveOperationException | ClassCastException | LinkageError failure) {
            failClosed("Could not read vanish event; Discord public presence is fail-closed", failure);
        }
    }

    private List<String> publicPlayerNames() {
        return publiclyOnline.playerNames();
    }

    @Override public void onDisable() {
        try {
            if (discord != null) discord.finalNotification("shutdown", Map.of("online", Integer.toString(publiclyOnline.size()), "max", "?"));
        } catch (RuntimeException failure) {
            getLogger().warning("Could not send LunaBridge shutdown notification");
        }
        closeBridge();
        closeVanishListener();
        publiclyOnline.clear();
    }

    private void closeBridge() {
        if (subscription != null) try { subscription.close(); } catch (RuntimeException failure) { getLogger().warning("Could not close LunaChat subscription"); }
        subscription = null;
        if (discord != null) try { discord.close(); } catch (RuntimeException failure) { getLogger().warning("Could not close Discord connector"); }
        discord = null;
    }

    private boolean onAdministrationCommand(@NotNull CommandSender sender, @NotNull Command command,
                                            @NotNull String label, @NotNull String[] arguments) {
        if (!(sender instanceof ConsoleCommandSender)) {
            sender.sendMessage("LunaBridge administration is console-only.");
            return true;
        }
        if (api == null || settings == null) {
            sender.sendMessage("FAIL LunaChat Integration API or LunaBridge settings are unavailable");
            return true;
        }
        if (arguments.length == 1 && "doctor".equalsIgnoreCase(arguments[0])) {
            try {
                BridgeAdministration.doctor(api, settings.discord, discord != null && discord.hasGateway(),
                        discord != null && discord.isReady()).forEach(sender::sendMessage);
            } catch (RuntimeException failure) {
                sender.sendMessage("FAIL doctor could not inspect LunaBridge state: " + failure.getMessage());
            }
            return true;
        }
        if (arguments.length == 3 && "setup".equalsIgnoreCase(arguments[0])) {
            try {
                var channel = BridgeAdministration.resolveSetup(api, arguments[1], arguments[2]);
                PaperSettings.saveMapping(this, arguments[1], channel.id().value());
                PaperSettings updated = PaperSettings.load(this);
                settings = updated;
                sender.sendMessage("OK mapped Discord " + arguments[1] + " -> "
                        + channel.name() + " (" + channel.id().value() + ")");
                if (discord == null) sender.sendMessage("WAIT mapping saved; restart the server to recover the bridge");
                else {
                    discord.reconfigure(updated.discord);
                    sender.sendMessage(discord.sendSetupTest(arguments[1], channel.name())
                            ? "OK Discord setup test queued" : "FAIL Discord gateway unavailable; mapping was saved");
                }
            } catch (RuntimeException failure) {
                sender.sendMessage("FAIL " + failure.getMessage());
            }
            return true;
        }
        sender.sendMessage("Usage: lunabridge doctor | lunabridge setup <discord-channel-id> <lunachat-name-or-alias>");
        return true;
    }
}
