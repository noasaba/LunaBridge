package dev.lunabridge.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VelocityPersistenceTest {
    @TempDir Path temporaryDirectory;

    @Test void firstLoginLedgerPersistsAndEpochFencesRestart() throws Exception {
        UUID player = UUID.randomUUID();
        SeenPlayerStore initial = new SeenPlayerStore(temporaryDirectory);
        assertTrue(initial.markFirst(player));
        assertFalse(new SeenPlayerStore(temporaryDirectory).markFirst(player));
        long firstEpoch = EpochStore.next(temporaryDirectory);
        assertTrue(EpochStore.next(temporaryDirectory) > firstEpoch);
    }

    @Test void futureConfigurationSchemaIsRejectedWithoutOverwrite() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.properties"), "config-version=99\nnetwork.shared-pass=keep\n");
        assertThrows(IllegalStateException.class, () -> VelocitySettings.load(temporaryDirectory));
        assertTrue(Files.readString(temporaryDirectory.resolve("config.properties")).contains("config-version=99"));
    }

    @Test void defaultBothPlayersModeEnablesTextAndSlashCommands() throws Exception {
        VelocitySettings settings = VelocitySettings.load(temporaryDirectory);
        assertTrue(JdaDiscordGateway.textPlayersEnabled(settings));
        assertTrue(JdaDiscordGateway.playersSlashEnabled(settings));
        settings.properties.setProperty("discord.commands.players.mode", "text");
        assertTrue(JdaDiscordGateway.textPlayersEnabled(settings));
        assertFalse(JdaDiscordGateway.playersSlashEnabled(settings));
    }

    @Test void utf8NotificationTemplatesArePreserved() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.properties"),
                "config-version=1\nnetwork.shared-pass=a-long-enough-isolated-test-passphrase\n"
                        + "discord.notifications.startup=✅ Server started\n", StandardCharsets.UTF_8);
        assertEquals("✅ Server started", VelocitySettings.load(temporaryDirectory).properties
                .getProperty("discord.notifications.startup"));
    }
}
