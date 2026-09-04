package dev.lunabridge.velocity;

import com.noasaba.svsync.api.SVSyncApi;
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
        assertTrue(persisted.contains("config-version=4"));
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
        assertTrue(persisted.contains("config-version=4"));
        assertTrue(persisted.contains("discord.channels.1307767610976243722.lunachat-channel-id="
                + "550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test void svsyncMakesUnknownAndVanishedPlayersNonPublic() {
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertTrue(!SVSyncVisibilityProvider.isPublic(state(false, false), playerId));
        assertTrue(!SVSyncVisibilityProvider.isPublic(state(true, true), playerId));
        assertTrue(SVSyncVisibilityProvider.isPublic(state(true, false), playerId));
    }

    private static SVSyncApi state(boolean hasState, boolean vanished) {
        return new SVSyncApi() {
            @Override public boolean hasState(UUID playerId) { return hasState; }
            @Override public boolean isVanished(UUID playerId) { return vanished; }
        };
    }
}
