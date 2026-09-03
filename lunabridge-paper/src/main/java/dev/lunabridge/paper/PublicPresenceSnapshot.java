package dev.lunabridge.paper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Thread-safe, Bukkit-free data exposed to Discord command threads. */
final class PublicPresenceSnapshot {
    private final ConcurrentMap<UUID, String> players = new ConcurrentHashMap<>();

    boolean markPublic(UUID playerId, String playerName) {
        return players.putIfAbsent(playerId, playerName) == null;
    }

    String markHidden(UUID playerId) {
        return players.remove(playerId);
    }

    void replace(Map<UUID, String> publicPlayers) {
        players.clear();
        players.putAll(new LinkedHashMap<>(publicPlayers));
    }

    void clear() { players.clear(); }

    int size() { return players.size(); }

    List<String> playerNames() { return List.copyOf(players.values()); }
}
