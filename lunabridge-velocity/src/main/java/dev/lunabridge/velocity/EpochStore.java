package dev.lunabridge.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Persisted epoch fences sessions from a previous Velocity process. */
final class EpochStore {
    private EpochStore() { }
    static long next(Path directory) throws IOException {
        Path file = directory.resolve("network-epoch");
        long previous = 0;
        if (Files.exists(file)) try { previous = Long.parseLong(Files.readString(file).trim()); } catch (NumberFormatException ignored) { }
        long next = Math.max(previous + 1, System.currentTimeMillis());
        Files.writeString(file, Long.toString(next), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return next;
    }
}
