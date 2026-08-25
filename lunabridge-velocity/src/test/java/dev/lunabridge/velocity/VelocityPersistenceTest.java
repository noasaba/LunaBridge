package dev.lunabridge.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.time.Instant;
import java.util.List;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;

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

    @Test void corruptFirstLoginLedgerFailsClosedAndPreservesEvidence() throws Exception {
        Path ledger = temporaryDirectory.resolve("seen-players.txt");
        Files.writeString(ledger, "partial-crash-record");
        assertThrows(java.io.IOException.class, () -> new SeenPlayerStore(temporaryDirectory));
        assertEquals("partial-crash-record", Files.readString(ledger));
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

    @Test void corruptEpochFailsClosedWithoutOverwritingEvidence() throws Exception {
        Path epoch = temporaryDirectory.resolve("network-epoch");
        Files.writeString(epoch, "not-a-number");
        assertThrows(java.io.IOException.class, () -> EpochStore.next(temporaryDirectory));
        assertEquals("not-a-number", Files.readString(epoch));
    }

    @Test void discordMessagesAreBoundedWithoutSplittingSurrogates() {
        String fitted = JdaDiscordGateway.fitDiscordMessage("a".repeat(1_998) + "😀" + "tail");
        assertTrue(fitted.length() <= 2_000);
        assertTrue(fitted.endsWith("…"));
        assertFalse(Character.isHighSurrogate(fitted.charAt(fitted.length() - 2)));
    }

    @Test void carrierSelectionIsResolvedAgainAfterAPlayerSwitch() {
        List<String> connections = List.of("old-backend", "new-backend");
        assertEquals("old-backend", VelocityNetworkAuthority.selectCurrentCarrier(connections,
                "old-backend"::equals));
        assertEquals("new-backend", VelocityNetworkAuthority.selectCurrentCarrier(connections,
                "new-backend"::equals));
    }

    @Test void discordIngressIsNeverRelayedBackToDiscord() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        BridgeMessage discord = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.DISCORD, "global", "Global",
                null, "Discord user", "hello", "discord", now, now.plusSeconds(10));
        BridgeMessage minecraft = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.MINECRAFT, "global", "Global",
                UUID.randomUUID(), "Player", "hello", "lobby", now, now.plusSeconds(10));
        assertFalse(VelocityNetworkAuthority.relaysToDiscord(discord));
        assertTrue(VelocityNetworkAuthority.relaysToDiscord(minecraft));
    }

    @Test void duplicateDiscordChannelIdsAreRejectedDeterministically() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.properties"),
                "config-version=1\nnetwork.shared-pass=a-long-enough-isolated-test-passphrase\n"
                        + "discord.channels.a=123456789012345678\n"
                        + "discord.channels.b=123456789012345678\n", StandardCharsets.UTF_8);
        assertThrows(IllegalStateException.class, () -> VelocitySettings.load(temporaryDirectory));
    }

    @Test void playerCommandsAreRestrictedToBridgeChannels() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.properties"),
                "config-version=1\nnetwork.shared-pass=a-long-enough-isolated-test-passphrase\n"
                        + "discord.channels.global=123456789012345678\n", StandardCharsets.UTF_8);
        VelocitySettings settings = VelocitySettings.load(temporaryDirectory);
        assertTrue(JdaDiscordGateway.isAllowedCommandChannel(settings, "123456789012345678"));
        assertFalse(JdaDiscordGateway.isAllowedCommandChannel(settings, "999999999999999999"));
    }
}
