package dev.lunabridge.velocity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** Small durable, capped first-login ledger. A full ledger reports existing players but never grows unbounded. */
final class SeenPlayerStore {
    private static final int MAX_ENTRIES = 50_000;
    private final Path file;
    private final Set<UUID> seen = new LinkedHashSet<>();
    SeenPlayerStore(Path dataDirectory) throws IOException {
        file = dataDirectory.resolve("seen-players.txt");
        if (Files.exists(file)) for (String line : Files.readAllLines(file)) {
            try { if (seen.size() < MAX_ENTRIES) seen.add(UUID.fromString(line.trim())); } catch (IllegalArgumentException ignored) { }
        }
    }
    synchronized boolean markFirst(UUID id) {
        if (seen.contains(id)) return false;
        if (seen.size() >= MAX_ENTRIES) return false;
        seen.add(id);
        try { Files.writeString(file, id + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND); }
        catch (IOException error) { seen.remove(id); return false; }
        return true;
    }
}
