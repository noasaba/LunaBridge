package dev.lunabridge.discord;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded, in-memory receipt cache for observer duplicate suppression. */
public final class MessageReceiptCache {
    private final int capacity;
    private final Duration ttl;
    private final Clock clock;
    private final LinkedHashMap<UUID, Instant> receipts = new LinkedHashMap<>();

    public MessageReceiptCache(int capacity, Duration ttl, Clock clock) {
        if (capacity < 1 || ttl.isZero() || ttl.isNegative()) throw new IllegalArgumentException("invalid receipt bounds");
        this.capacity = capacity;
        this.ttl = ttl;
        this.clock = clock;
    }

    public synchronized boolean markIfNew(UUID messageId) {
        cleanup(Instant.now(clock));
        if (receipts.containsKey(messageId)) return false;
        if (receipts.size() >= capacity) return false;
        receipts.put(messageId, Instant.now(clock).plus(ttl));
        return true;
    }

    public synchronized int size() {
        cleanup(Instant.now(clock));
        return receipts.size();
    }

    public synchronized int cleanupExpired() {
        int before = receipts.size();
        cleanup(Instant.now(clock));
        return before - receipts.size();
    }

    private void cleanup(Instant now) {
        Iterator<Map.Entry<UUID, Instant>> iterator = receipts.entrySet().iterator();
        while (iterator.hasNext()) if (!iterator.next().getValue().isAfter(now)) iterator.remove();
    }
}
