package dev.lunabridge.velocity;

import com.noasaba.svsync.api.SVSyncApi;
import com.velocitypowered.api.network.ListenerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySettingsTest {
    @TempDir Path directory;

    @Test void olderSchemaMigratesAdditivelyAndTokenFileWins() throws Exception {
        Files.writeString(directory.resolve("token"), "file-token\n");
        Files.writeString(directory.resolve("config.properties"), """
                config-version=2
                discord.token=inline-token
                discord.token-file=token
                discord.channels.1307767610976243722.lunachat-channel-id=550e8400-e29b-41d4-a716-446655440000
                """);
        VelocitySettings settings = VelocitySettings.load(directory);
        assertEquals("file-token", settings.discord.token());
        assertEquals("550e8400-e29b-41d4-a716-446655440000",
                settings.discord.discordChannelToLunaChatChannelId().get("1307767610976243722"));
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("config-version=5"));
        assertEquals("Discord:{username}", settings.discord.option("discord.external-display-name-format", ""));
        assertTrue(persisted.contains("discord.minecraft-chat-format="));
        assertEquals("[{channel}] {username}: {message}{japanized}",
                settings.discord.option("discord.minecraft-chat-format", ""));
    }

    @Test void setupPersistsStableIdWithoutRemovingExistingConfiguration() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "config-version=3\ndiscord.token=keep-me\n");
        VelocitySettings.saveMapping(directory, "1307767610976243722",
                "550e8400-e29b-41d4-a716-446655440000");
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("discord.token=keep-me"));
        assertTrue(persisted.contains("config-version=5"));
        assertTrue(persisted.contains("discord.channels.1307767610976243722.lunachat-channel-id="
                + "550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test void mappingUpdatesPreserveCommentsAndCanBeRemoved() throws Exception {
        Files.writeString(directory.resolve("config.properties"), """
                # Keep this operator note
                config-version=5
                discord.token=keep-me
                discord.minecraft-chat-format=[{channel}] {username}: {message}{japanized}
                discord.external-display-name-format=Discord:{username}
                """);
        VelocitySettings.saveMapping(directory, "1307767610976243722",
                "550e8400-e29b-41d4-a716-446655440000");
        assertTrue(Files.readString(directory.resolve("config.properties")).contains("# Keep this operator note"));
        VelocitySettings.removeMapping(directory, "1307767610976243722");
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("# Keep this operator note"));
        assertTrue(!persisted.contains("discord.channels.1307767610976243722"));
    }

    @Test void svsyncMakesUnknownAndVanishedPlayersNonPublic() {
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertTrue(SVSyncVisibilityProvider.isPublic(state(false, false), playerId));
        assertTrue(!SVSyncVisibilityProvider.isPublic(state(true, true), playerId));
        assertTrue(SVSyncVisibilityProvider.isPublic(state(true, false), playerId));
    }

    @Test void administrationAllowsConsoleAndAuthorizedPlayersOnly() {
        assertTrue(LunaBridgeVelocityPlugin.isAdministrationAuthorized(true, false));
        assertTrue(LunaBridgeVelocityPlugin.isAdministrationAuthorized(false, true));
        assertTrue(!LunaBridgeVelocityPlugin.isAdministrationAuthorized(false, false));
    }

    @Test void disconnectUsesLastKnownVisibilityWhenSVSyncAlreadyRemovedState() {
        assertTrue(LunaBridgeVelocityPlugin.isPublicAtDisconnect(
                SVSyncVisibilityProvider.Visibility.UNKNOWN, true));
        assertTrue(!LunaBridgeVelocityPlugin.isPublicAtDisconnect(
                SVSyncVisibilityProvider.Visibility.UNKNOWN, false));
        assertTrue(!LunaBridgeVelocityPlugin.isPublicAtDisconnect(
                SVSyncVisibilityProvider.Visibility.HIDDEN, true));
    }

    @Test void discordLifecycleIsOwnedOnlyByTheMinecraftListener() {
        assertTrue(LunaBridgeVelocityPlugin.ownsDiscordLifecycle(ListenerType.MINECRAFT));
        assertTrue(!LunaBridgeVelocityPlugin.ownsDiscordLifecycle(ListenerType.QUERY));
    }

    private static SVSyncApi state(boolean hasState, boolean vanished) {
        return new SVSyncApi() {
            @Override public boolean hasState(UUID playerId) { return hasState; }
            @Override public boolean isVanished(UUID playerId) { return vanished; }
        };
    }
}
