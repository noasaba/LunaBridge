package dev.lunabridge.velocity;

import dev.lunachat.api.LunaChatIntegrationApi.AcceptedMessage;

import java.util.Map;

/** One implementation instance is owned by Velocity; Paper never constructs this capability. */
interface DiscordGateway {
    void relayMinecraft(AcceptedMessage message);
    void notification(String type, Map<String, String> placeholders);
    default void finalNotification(String type, Map<String, String> placeholders) {
        notification(type, placeholders);
        close();
    }
    void close();
    static DiscordGateway disabled() {
        return new DiscordGateway() {
            @Override public void relayMinecraft(AcceptedMessage message) { }
            @Override public void notification(String type, Map<String, String> placeholders) { }
            @Override public void close() { }
        };
    }
}
