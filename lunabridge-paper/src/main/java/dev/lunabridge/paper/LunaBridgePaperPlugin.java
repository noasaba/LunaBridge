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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Paper standalone adapter; it never observes legacy chat events or owns Minecraft transport. */
public final class LunaBridgePaperPlugin extends JavaPlugin implements Listener {
    private DiscordConnector discord;
    private Subscription subscription;
    private LunaChatIntegrationApi api;
    private PaperSettings settings;
    private final Set<UUID> publiclyOnline = ConcurrentHashMap.newKeySet();
    private PublicVisibilityProvider visibilityProvider = new DefaultVisibilityProvider();

    @Override public void onEnable() {
        var command = getCommand("lunabridge");
        if (command == null) throw new IllegalStateException("lunabridge command metadata missing");
        command.setExecutor(this::onAdministrationCommand);
        try {
            settings = PaperSettings.load(this);
            RegisteredServiceProvider<LunaChatIntegrationApi> registration =
                    Bukkit.getServicesManager().getRegistration(LunaChatIntegrationApi.class);
            api = registration == null ? null : registration.getProvider();
            AuthorityValidation.require(api, RuntimeRole.STANDALONE_AUTHORITY);
            for (String channelId : settings.discord.discordChannelToLunaChatChannelId().values()) {
                api.channels().find(new ChannelId(channelId)).orElseThrow(
                        () -> new IllegalStateException("Unknown LunaChat ChannelId " + channelId));
            }
            visibilityProvider = createVisibilityProvider();
            publiclyOnline.clear();
            Bukkit.getOnlinePlayers().forEach(player -> {
                if (visibilityProvider.isPublic(player)) publiclyOnline.add(player.getUniqueId());
            });
            PlayerDirectory players = this::publicPlayerNames;
            discord = DiscordConnector.start(api, players, settings.discord, getSLF4JLogger());
            subscription = api.messages().observeAcceptedMessages(discord::relayMinecraft);
            Bukkit.getPluginManager().registerEvents(this, this);
            discord.notification("startup", Map.of("online", Integer.toString(publiclyOnline.size()), "max", "?"));
            getLogger().info("LunaBridge Paper standalone enabled as a Discord-only LunaChat API consumer.");
        } catch (RuntimeException failure) {
            closeBridge();
            getLogger().severe("LunaBridge Paper standalone refused to start before Discord connection: " + failure.getMessage());
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        if (!visibilityProvider.isPublic(event.getPlayer()) || !publiclyOnline.add(event.getPlayer().getUniqueId())) return;
        if (discord != null) discord.notification("join", Map.of("player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString(), "server", Bukkit.getServer().getName()));
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        if (!publiclyOnline.remove(event.getPlayer().getUniqueId())) return;
        if (discord != null) discord.notification("quit", Map.of("player", event.getPlayer().getName(),
                "uuid", event.getPlayer().getUniqueId().toString(), "server", Bukkit.getServer().getName()));
    }

    private PublicVisibilityProvider createVisibilityProvider() {
        if (!Bukkit.getPluginManager().isPluginEnabled("SuperVanish")
                && !Bukkit.getPluginManager().isPluginEnabled("PremiumVanish")) {
            return new DefaultVisibilityProvider();
        }
        try {
            PublicVisibilityProvider provider = new SuperVanishVisibilityProvider();
            registerVanishEvent("de.myzelyam.api.vanish.PostPlayerHideEvent", false);
            registerVanishEvent("de.myzelyam.api.vanish.PostPlayerShowEvent", true);
            getLogger().info("SuperVanish public presence integration enabled.");
            return provider;
        } catch (IllegalStateException | LinkageError failure) {
            getLogger().warning("Vanish plugin detected but its public API is unavailable; public presence filtering is disabled.");
            return new DefaultVisibilityProvider();
        }
    }

    @SuppressWarnings("unchecked")
    private void registerVanishEvent(String className, boolean shown) {
        try {
            Class<? extends Event> eventType = (Class<? extends Event>) Class.forName(className);
            EventExecutor executor = (listener, event) -> handleVanishEvent(event, shown);
            Bukkit.getPluginManager().registerEvent(eventType, this, EventPriority.MONITOR, executor, this, true);
        } catch (ClassNotFoundException | LinkageError missingEvent) {
            throw new IllegalStateException("Missing SuperVanish event " + className, missingEvent);
        }
    }

    private void handleVanishEvent(Event event, boolean shown) {
        try {
            Player player = (Player) event.getClass().getMethod("getPlayer").invoke(event);
            if (!player.isOnline()) return;
            if (shown && visibilityProvider.isPublic(player)) publiclyOnline.add(player.getUniqueId());
            else publiclyOnline.remove(player.getUniqueId());
        } catch (ReflectiveOperationException | ClassCastException failure) {
            getLogger().warning("Could not read player from vanish event: " + failure.getMessage());
        }
    }

    private List<String> publicPlayerNames() {
        return Bukkit.getOnlinePlayers().stream()
                .filter(player -> publiclyOnline.contains(player.getUniqueId()))
                .map(Player::getName).toList();
    }

    @Override public void onDisable() {
        if (discord != null) discord.finalNotification("shutdown", Map.of("online", Integer.toString(publiclyOnline.size()), "max", "?"));
        closeBridge();
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
            BridgeAdministration.doctor(api, settings.discord, discord != null && discord.hasGateway(),
                    discord != null && discord.isReady()).forEach(sender::sendMessage);
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
