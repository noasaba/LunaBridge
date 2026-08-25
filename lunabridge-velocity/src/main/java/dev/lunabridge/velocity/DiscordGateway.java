package dev.lunabridge.velocity;

import dev.lunabridge.core.model.BridgeMessage;

import java.util.Map;

/** One implementation instance is owned by Velocity; Paper never constructs this capability. */
interface DiscordGateway {
    void relayMinecraft(BridgeMessage message);
    void notification(String type, Map<String, String> placeholders);
    default void finalNotification(String type, Map<String, String> placeholders) {
        notification(type, placeholders);
        close();
    }
    void close();
    static DiscordGateway disabled() {
        return new DiscordGateway() {
            @Override public void relayMinecraft(BridgeMessage message) { }
            @Override public void notification(String type, Map<String, String> placeholders) { }
            @Override public void close() { }
        };
    }
}
