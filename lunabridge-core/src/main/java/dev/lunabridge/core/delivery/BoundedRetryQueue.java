package dev.lunabridge.core.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Deadline-bound in-memory retry work. Queue pressure is explicit, never an OOM strategy. */
public final class BoundedRetryQueue<T> {
    public enum Offer { ACCEPTED, FULL, EXPIRED }
    public record Item<T>(UUID id, T value, int attempt, Instant deadline, Instant dueAt) { }
    private final int maxEntries;
    private final int maxAttempts;
    private final Clock clock;
    private final List<Item<T>> entries = new ArrayList<>();

    public BoundedRetryQueue(int maxEntries, int maxAttempts, Clock clock) {
        if (maxEntries < 1 || maxAttempts < 1) throw new IllegalArgumentException("invalid queue limits");
        this.maxEntries = maxEntries;
        this.maxAttempts = maxAttempts;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Offer offer(UUID id, T value, Instant deadline) {
        Instant now = clock.instant();
        purgeExpired(now);
        if (!deadline.isAfter(now)) return Offer.EXPIRED;
        if (entries.size() >= maxEntries) return Offer.FULL;
        entries.add(new Item<>(id, value, 0, deadline, now));
        return Offer.ACCEPTED;
    }

    public synchronized List<Item<T>> takeDue() {
        Instant now = clock.instant();
        purgeExpired(now);
        List<Item<T>> due = entries.stream().filter(item -> !item.dueAt().isAfter(now)).toList();
        entries.removeAll(due);
        return due;
    }

    public synchronized Offer retry(Item<T> previous, Duration delay) {
        Instant now = clock.instant();
        if (previous.attempt() + 1 >= maxAttempts || !previous.deadline().isAfter(now)) return Offer.EXPIRED;
        if (entries.size() >= maxEntries) return Offer.FULL;
        entries.add(new Item<>(previous.id(), previous.value(), previous.attempt() + 1,
                previous.deadline(), now.plus(delay)));
        entries.sort(Comparator.comparing(Item::dueAt));
        return Offer.ACCEPTED;
    }

    public synchronized int size() { return entries.size(); }

    private void purgeExpired(Instant now) { entries.removeIf(item -> !item.deadline().isAfter(now)); }
}
