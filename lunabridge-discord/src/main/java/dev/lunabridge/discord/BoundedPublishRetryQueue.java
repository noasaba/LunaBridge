package dev.lunabridge.discord;

import com.github.ucchyocean.lunachat.api.ExternalMessageRequest;
import com.github.ucchyocean.lunachat.api.ExternalPublishResult;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Bounded retry policy for LunaChat external publish calls. */
public final class BoundedPublishRetryQueue implements AutoCloseable {
    @FunctionalInterface
    public interface Publisher {
        CompletionStage<ExternalPublishResult> publish(ExternalMessageRequest request);
    }

    private record Pending(ExternalMessageRequest request, int attempts) { }

    private final int capacity;
    private final int maxAttempts;
    private final Publisher publisher;
    private final ScheduledExecutorService executor;
    private final Clock clock;
    private final Map<String, Pending> pending = new HashMap<>();
    private boolean closed;

    public BoundedPublishRetryQueue(int capacity, int maxAttempts, Publisher publisher,
                                    ScheduledExecutorService executor, Clock clock) {
        if (capacity < 1 || maxAttempts < 1) throw new IllegalArgumentException("invalid retry bounds");
        this.capacity = capacity;
        this.maxAttempts = maxAttempts;
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public boolean submit(ExternalMessageRequest request) {
        String key = key(request);
        synchronized (this) {
            if (closed || pending.containsKey(key) || pending.size() >= capacity) return false;
            pending.put(key, new Pending(request, 0));
        }
        attempt(key);
        return true;
    }

    public synchronized int pendingCount() { return pending.size(); }

    @Override public void close() {
        synchronized (this) {
            closed = true;
            pending.clear();
        }
        executor.shutdownNow();
    }

    private void attempt(String key) {
        Pending current;
        synchronized (this) {
            current = pending.get(key);
            if (closed || current == null) return;
            if (expired(current.request())) { pending.remove(key); return; }
            pending.put(key, new Pending(current.request(), current.attempts() + 1));
        }
        CompletionStage<ExternalPublishResult> stage;
        try {
            stage = publisher.publish(current.request());
        } catch (RuntimeException failure) {
            complete(key, null, failure);
            return;
        }
        stage.whenComplete((result, failure) -> complete(key, result, failure));
    }

    private void complete(String key, ExternalPublishResult result, Throwable failure) {
        Pending current;
        long delayMillis;
        synchronized (this) {
            current = pending.get(key);
            if (closed || current == null) return;
            boolean successful = failure == null && result != null
                    && (result.status() == com.github.ucchyocean.lunachat.api.PublishStatus.ACCEPTED
                    || result.status() == com.github.ucchyocean.lunachat.api.PublishStatus.DUPLICATE);
            boolean resultCanRetry = failure != null || result != null && result.retryable()
                    && (result.status() == com.github.ucchyocean.lunachat.api.PublishStatus.OVER_CAPACITY
                    || result.status() == com.github.ucchyocean.lunachat.api.PublishStatus.UNAVAILABLE);
            if (successful || !resultCanRetry || current.attempts() >= maxAttempts || expired(current.request())) {
                pending.remove(key);
                return;
            }
            delayMillis = Math.min(5_000L, 250L << Math.min(4, Math.max(0, current.attempts() - 1)));
            Duration remaining = Duration.between(Instant.now(clock), current.request().createdAt()
                    .plus(current.request().requestedLifetime()));
            if (remaining.isNegative() || remaining.isZero()) { pending.remove(key); return; }
            delayMillis = Math.min(delayMillis, Math.max(1L, remaining.toMillis()));
        }
        executor.schedule(() -> attempt(key), delayMillis, TimeUnit.MILLISECONDS);
    }

    private boolean expired(ExternalMessageRequest request) {
        return !request.createdAt().plus(request.requestedLifetime()).isAfter(Instant.now(clock));
    }

    private static String key(ExternalMessageRequest request) {
        return request.identity().namespace() + '\u0000' + request.identity().value();
    }
}
