package dev.lunabridge.paper;

import com.github.ucchyocean.lc3.LunaChat;
import com.github.ucchyocean.lc3.bukkit.event.LunaChatBukkitChannelMessageEvent;
import dev.lunabridge.core.delivery.BoundedDeliveryTracker;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Uses only LunaChat's documented API/events; it never listens to or cancels Paper chat events. */
final class LunaChatAdapter implements Listener {
    private final JavaPlugin plugin;
    private final PaperSettings settings;
    private final PaperNetworkClient network;
    private final Clock clock = Clock.systemUTC();
    private final BoundedDeliveryTracker inboundDeliveries;

    LunaChatAdapter(JavaPlugin plugin, PaperSettings settings, PaperNetworkClient network) {
        this.plugin = plugin;
        this.settings = settings;
        this.network = network;
        this.inboundDeliveries = new BoundedDeliveryTracker(settings.dedupLimit, Duration.ofSeconds(30),
                Duration.ofHours(24), clock);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onConfirmedLunaChatMessage(LunaChatBukkitChannelMessageEvent event) {
        if (RemoteInjectionScope.active() || event.getMember() == null) return;
        String bridgeKey = settings.channels.bridgeKeyForLunaChannel(event.getChannelName()).orElse(null);
        if (bridgeKey == null) return;
        // LunaChat can fire asynchronously. Capture only immutable values, then resolve Bukkit identity on its main thread.
        String channel = event.getChannelName();
        String memberName = event.getMember().getName();
        String displayName = event.getDisplayName();
        String message = event.getMessage();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player player = Bukkit.getPlayerExact(memberName);
            if (player == null || !player.isOnline()) return; // Never promote a display name to an identity.
            Instant now = Instant.now();
            BridgeMessage bridgeMessage = new BridgeMessage(UUID.randomUUID(), UUID.randomUUID(), BridgeOrigin.MINECRAFT,
                    bridgeKey, channel, player.getUniqueId(), displayName, message, settings.serverId, now, now.plusSeconds(10));
            network.publish(bridgeMessage);
        });
    }

    void inject(BridgeMessage message, Runnable acknowledged) {
        BoundedDeliveryTracker.Admission admission = inboundDeliveries.begin(message.id());
        if (admission == BoundedDeliveryTracker.Admission.DELIVERED) { acknowledged.run(); return; }
        if (admission != BoundedDeliveryTracker.Admission.NEW) return;
        String lunaChannel = settings.channels.lunaChannelForBridgeKey(message.bridgeChannel()).orElse(null);
        if (lunaChannel == null) { inboundDeliveries.fail(message.id()); return; }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (!message.expiresAt().isAfter(clock.instant())) { inboundDeliveries.fail(message.id()); return; }
                    var channel = LunaChat.getAPI().getChannel(lunaChannel);
                    if (channel == null) { inboundDeliveries.fail(message.id()); return; }
                    RemoteInjectionScope.run(() -> channel.chatFromOtherSource(message.authorName(), message.sourceServer(), message.content()));
                    inboundDeliveries.complete(message.id());
                    acknowledged.run(); // dispatch completed; does not claim client rendering.
                } catch (RuntimeException failed) {
                    inboundDeliveries.fail(message.id());
                    plugin.getLogger().warning("LunaBridge could not dispatch a remote message through LunaChat.");
                }
            });
        } catch (RuntimeException unavailable) {
            inboundDeliveries.fail(message.id());
            plugin.getLogger().warning("LunaBridge could not schedule a remote LunaChat dispatch.");
        }
    }
}
