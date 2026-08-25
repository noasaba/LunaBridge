package dev.lunabridge.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySettingsTest {
    @TempDir Path directory;

    @Test void schemaTwoMigratesAdditivelyAndTokenFileWins() throws Exception {
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
        assertTrue(Files.readString(directory.resolve("config.properties")).contains("config-version=3"));
    }

    @Test void setupPersistsStableIdWithoutRemovingExistingConfiguration() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "config-version=3\ndiscord.token=keep-me\n");
        VelocitySettings.saveMapping(directory, "1307767610976243722",
                "550e8400-e29b-41d4-a716-446655440000");
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("discord.token=keep-me"));
        assertTrue(persisted.contains("discord.channels.1307767610976243722.lunachat-channel-id="
                + "550e8400-e29b-41d4-a716-446655440000"));
    }
}
