package dev.lunabridge.paper;

import com.github.ucchyocean.lc3.LunaChat;
import com.github.ucchyocean.lc.event.LunaChatChannelMessageEvent;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/** Uses LunaChat's public API and its actual final compatibility event; it never cancels Paper chat events. */
@SuppressWarnings("deprecation") // LunaChat 3.0.16's legacy-compatible event is its actual final mutable boundary.
final class LunaChatAdapter implements Listener {
    private final JavaPlugin plugin;
    private final PaperSettings settings;
    private final PaperNetworkClient network;
    private final Clock clock = Clock.systemUTC();

    record FinalMessage(String channel, String memberName, String displayName, String content,
                        boolean hasLocalRecipients) { }

    LunaChatAdapter(JavaPlugin plugin, PaperSettings settings, PaperNetworkClient network) {
        this.plugin = plugin;
        this.settings = settings;
        this.network = network;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onFinalLunaChatMessage(LunaChatChannelMessageEvent event) {
        if (RemoteInjectionScope.active() || event.getPlayer() == null) return;
        // LunaChat fires this legacy-compatible event after its v3 event. Read it on the next main-thread task so
        // every handler, including later MONITOR handlers, has finished applying the final message mutation.
        Bukkit.getScheduler().runTask(plugin, () -> {
            FinalMessage finalMessage = snapshot(event);
            if (!finalMessage.hasLocalRecipients()) return;
            String bridgeKey = settings.channels.bridgeKeyForLunaChannel(finalMessage.channel()).orElse(null);
            if (bridgeKey == null) return;
            Player player = Bukkit.getPlayerExact(finalMessage.memberName());
            if (player == null || !player.isOnline()) return; // Never promote a display name to an identity.
            Instant now = clock.instant();
            BridgeMessage bridgeMessage = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.MINECRAFT,
                    bridgeKey, finalMessage.channel(), player.getUniqueId(), finalMessage.displayName(),
                    finalMessage.content(), settings.serverId, now, now.plusSeconds(10));
            network.publish(bridgeMessage);
        });
    }

    static FinalMessage snapshot(LunaChatChannelMessageEvent event) {
        return new FinalMessage(event.getChannelName(), event.getPlayer().getName(), event.getDisplayName(),
                event.getMessage(), event.getRecipients() != null && !event.getRecipients().isEmpty());
    }

    void inject(BridgeMessage message, Runnable delivered, Runnable failed) {
        String lunaChannel = settings.channels.lunaChannelForBridgeKey(message.bridgeChannel()).orElse(null);
        if (lunaChannel == null) { failed.run(); return; }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!message.expiresAt().isAfter(clock.instant())) { failed.run(); return; }
                    var channel = LunaChat.getAPI().getChannel(lunaChannel);
                    if (channel == null) { failed.run(); return; }
                    RemoteInjectionScope.run(() -> channel.chatFromOtherSource(message.authorName(), message.sourceServer(), message.content()));
                    delivered.run(); // LunaChat dispatch completed; this does not claim client rendering.
                } catch (RuntimeException dispatchFailure) {
                    failed.run();
                    plugin.getLogger().warning("LunaBridge could not dispatch a remote message through LunaChat.");
                }
            });
        } catch (RuntimeException unavailable) {
            failed.run();
            plugin.getLogger().warning("LunaBridge could not schedule a remote LunaChat dispatch.");
        }
    }
}
