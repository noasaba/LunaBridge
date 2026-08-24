package dev.lunabridge.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Persisted epoch fences sessions from a previous Velocity process. */
final class EpochStore {
    private EpochStore() { }
    static long next(Path directory) throws IOException {
        Path file = directory.resolve("network-epoch");
        long previous = 0;
        if (Files.exists(file)) {
            try { previous = Long.parseLong(Files.readString(file).trim()); }
            catch (NumberFormatException invalid) { throw new IOException("invalid persisted network epoch", invalid); }
        }
        if (previous < 0 || previous == Long.MAX_VALUE) throw new IOException("persisted network epoch is out of range");
        long next = Math.max(previous + 1, System.currentTimeMillis());
        Path temporary = Files.createTempFile(directory, "network-epoch-", ".tmp");
        try {
            Files.writeString(temporary, Long.toString(next), StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return next;
    }
}
