package dev.lunabridge.core.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Bounded inbound delivery state: duplicates are acknowledged only after the first dispatch completes. */
public final class BoundedDeliveryTracker {
    public enum Admission { NEW, IN_PROGRESS, DELIVERED, FULL }
    private enum State { IN_PROGRESS, DELIVERED }
    private record Entry(State state, Instant expiresAt) { }

    private final int maxEntries;
    private final Duration inProgressTtl;
    private final Duration deliveredTtl;
    private final Clock clock;
    private final Map<UUID, Entry> entries = new HashMap<>();

    public BoundedDeliveryTracker(int maxEntries, Duration inProgressTtl, Duration deliveredTtl, Clock clock) {
        if (maxEntries < 1 || inProgressTtl.isZero() || inProgressTtl.isNegative()
                || deliveredTtl.isZero() || deliveredTtl.isNegative()) {
            throw new IllegalArgumentException("invalid delivery tracker limits");
        }
        this.maxEntries = maxEntries;
        this.inProgressTtl = inProgressTtl;
        this.deliveredTtl = deliveredTtl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Admission begin(UUID id) {
        Objects.requireNonNull(id, "id");
        Instant now = clock.instant();
        evictExpired(now);
        Entry existing = entries.get(id);
        if (existing != null) return existing.state() == State.DELIVERED ? Admission.DELIVERED : Admission.IN_PROGRESS;
        if (entries.size() >= maxEntries) return Admission.FULL;
        entries.put(id, new Entry(State.IN_PROGRESS, now.plus(inProgressTtl)));
        return Admission.NEW;
    }

    public synchronized boolean complete(UUID id) {
        evictExpired(clock.instant());
        Entry existing = entries.get(id);
        if (existing == null || existing.state() != State.IN_PROGRESS) return false;
        entries.put(id, new Entry(State.DELIVERED, clock.instant().plus(deliveredTtl)));
        return true;
    }

    public synchronized void fail(UUID id) {
        Entry existing = entries.get(id);
        if (existing != null && existing.state() == State.IN_PROGRESS) entries.remove(id);
    }

    public synchronized int size() {
        evictExpired(clock.instant());
        return entries.size();
    }

    private void evictExpired(Instant now) {
        Iterator<Entry> values = entries.values().iterator();
        while (values.hasNext()) if (!values.next().expiresAt().isAfter(now)) values.remove();
    }
}
