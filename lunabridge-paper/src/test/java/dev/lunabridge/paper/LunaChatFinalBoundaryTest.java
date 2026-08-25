package dev.lunabridge.paper;

import com.github.ucchyocean.lc.channel.ChannelPlayer;
import com.github.ucchyocean.lc.event.LunaChatChannelMessageEvent;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the final legacy-compatible LunaChat event boundary used after the mutable v3 event. */
@SuppressWarnings("deprecation")
class LunaChatFinalBoundaryTest {
    @BeforeAll static void installMinimalBukkitServer() throws Exception {
        Server server = (Server) Proxy.newProxyInstance(Server.class.getClassLoader(), new Class<?>[] {Server.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "isPrimaryThread" -> true;
                    case "getLogger" -> Logger.getLogger("LunaBridge-test");
                    case "getName", "getVersion", "getBukkitVersion", "getMinecraftVersion" -> "test";
                    default -> primitiveDefault(method.getReturnType());
                });
        // Paper's public setter also renders build metadata that is intentionally absent in unit tests.
        var serverField = Bukkit.class.getDeclaredField("server");
        serverField.setAccessible(true);
        serverField.set(null, server);
    }

    @Test void snapshotReadsMutationAppliedAtFinalEventBoundary() {
        ArrayList<ChannelPlayer> recipients = new ArrayList<>();
        recipients.add(new StubChannelPlayer());
        LunaChatChannelMessageEvent event = new LunaChatChannelMessageEvent("global", new StubChannelPlayer(),
                "v3-value", recipients, "Alice Display", "original");
        event.setMessage("legacy-final-value"); // Simulates a downstream legacy LunaChat integration.

        LunaChatAdapter.FinalMessage snapshot = LunaChatAdapter.snapshot(event);
        assertEquals("global", snapshot.channel());
        assertEquals("Alice", snapshot.memberName());
        assertEquals("Alice Display", snapshot.displayName());
        assertEquals("legacy-final-value", snapshot.content());
        assertTrue(snapshot.hasLocalRecipients());
    }

    @Test void finalRecipientRemovalSuppressesNetworkPublication() {
        LunaChatChannelMessageEvent event = new LunaChatChannelMessageEvent("global", new StubChannelPlayer(),
                "message", new ArrayList<>(), "Alice Display", "original");
        assertFalse(LunaChatAdapter.snapshot(event).hasLocalRecipients());
    }

    private static final class StubChannelPlayer extends ChannelPlayer {
        @Override public boolean isOnline() { return true; }
        @Override public String getName() { return "Alice"; }
        @Override public String getDisplayName() { return "Alice Display"; }
        @Override public String getPrefix() { return ""; }
        @Override public String getSuffix() { return ""; }
        @Override public void sendMessage(String message) { }
        @Override public Player getPlayer() { return null; }
        @Override public String getWorldName() { return "world"; }
        @Override public Location getLocation() { return null; }
        @Override public boolean hasPermission(String permission) { return true; }
        @Override public boolean isPermissionSet(String permission) { return true; }
        @Override public boolean equals(CommandSender sender) { return false; }
        @Override public String toString() { return "Alice"; }
    }

    private static Object primitiveDefault(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
