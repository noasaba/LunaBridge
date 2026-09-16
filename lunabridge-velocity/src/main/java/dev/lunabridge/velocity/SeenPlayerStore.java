package dev.lunabridge.velocity;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Small durable, capped first-login ledger. A full ledger reports existing players but never grows unbounded. */
final class SeenPlayerStore {
    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private final Path file;
    private final Set<UUID> seen = new LinkedHashSet<>();
    SeenPlayerStore(Path dataDirectory) throws IOException {
        file = dataDirectory.resolve("seen-players.txt");
        if (Files.exists(file) && Files.size(file) > MAX_FILE_BYTES) throw new IOException("seen-player ledger is too large");
        if (Files.exists(file)) try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                final UUID id;
                try { id = UUID.fromString(line.trim()); }
                catch (IllegalArgumentException invalid) {
                    throw new IOException("seen-player ledger contains an invalid UUID; evidence was preserved", invalid);
                }
                if (seen.size() >= MAX_ENTRIES && !seen.contains(id)) {
                    throw new IOException("seen-player ledger exceeds its entry bound");
                }
                seen.add(id);
            }
        }
    }
    synchronized boolean isSeen(UUID id) { return seen.contains(id); }

    synchronized boolean markFirst(UUID id) throws IOException {
        if (seen.contains(id)) return false;
        if (seen.size() >= MAX_ENTRIES) return false;
        byte[] line = (id + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
            ByteBuffer bytes = ByteBuffer.wrap(line);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        seen.add(id); // Memory reflects the identity only after its durable append.
        return true;
    }
}
