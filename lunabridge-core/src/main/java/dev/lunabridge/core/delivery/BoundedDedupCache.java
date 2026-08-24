package dev.lunabridge.core.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Never discards a live idempotency record merely to make room for another one. */
public final class BoundedDedupCache {
    public enum Result { NEW, DUPLICATE, FULL }
    private final int maxEntries;
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, Instant> entries = new HashMap<>();

    public BoundedDedupCache(int maxEntries, Duration ttl, Clock clock) {
        if (maxEntries < 1 || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("invalid dedup limits");
        this.maxEntries = maxEntries;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized Result admit(UUID id) {
        Instant now = clock.instant();
        evictExpired(now);
        if (entries.containsKey(id)) return Result.DUPLICATE;
        if (entries.size() >= maxEntries) return Result.FULL;
        entries.put(id, now.plus(ttl));
        return Result.NEW;
    }

    public synchronized int size() { return entries.size(); }

    private void evictExpired(Instant now) {
        Iterator<Instant> values = entries.values().iterator();
        while (values.hasNext()) if (!values.next().isAfter(now)) values.remove();
    }
}
