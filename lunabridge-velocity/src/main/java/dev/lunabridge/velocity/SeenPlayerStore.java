package dev.lunabridge.velocity;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
            while (seen.size() < MAX_ENTRIES && (line = reader.readLine()) != null) {
                try { seen.add(UUID.fromString(line.trim())); } catch (IllegalArgumentException ignored) { }
            }
        }
    }
    synchronized boolean markFirst(UUID id) {
        if (seen.contains(id)) return false;
        if (seen.size() >= MAX_ENTRIES) return false;
        seen.add(id);
        try { Files.writeString(file, id + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException error) { seen.remove(id); return false; }
        return true;
    }
}
