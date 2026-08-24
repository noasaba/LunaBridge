package dev.lunabridge.velocity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
}
