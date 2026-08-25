package dev.lunabridge.core.delivery;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One bounded contract for session lifecycle, logical retries, acknowledgements and inbound idempotency.
 * A retry exposes the same logical id again; callers must create a fresh secure frame for every attempt.
 */
public final class DeliveryStateMachine<T> implements AutoCloseable {
    public enum SessionPhase { DISCONNECTED, HANDSHAKING, ACTIVE, CLOSED }
    public enum EnqueueResult { ACCEPTED, DUPLICATE, FULL, EXPIRED, CLOSED }
    public enum InboundDecision { NEW, IN_PROGRESS, DELIVERED, FULL, EXPIRED, CLOSED }

    public record Attempt<T>(UUID logicalId, T payload, int attempt, Instant deadline) {
        public Attempt { Objects.requireNonNull(logicalId); Objects.requireNonNull(payload); Objects.requireNonNull(deadline); }
    }

    private static final class Outbound<T> {
        private final T payload;
        private final Instant deadline;
        private DeliveryState state = DeliveryState.QUEUED;
        private int attempts;
        private Instant dueAt;

        private Outbound(T payload, Instant deadline, Instant dueAt) {
            this.payload = payload;
            this.deadline = deadline;
            this.dueAt = dueAt;
        }
    }

    private static final class Inbound {
        private DeliveryState state = DeliveryState.INBOUND_PROCESSING;
        private final Instant retainUntil;

        private Inbound(Instant retainUntil) { this.retainUntil = retainUntil; }
    }

    private final int outboundLimit;
    private final int inboundLimit;
    private final int maxAttemptsPerSession;
    private final Duration retryBase;
    private final Duration idempotencyGrace;
    private final Clock clock;
    private final Map<UUID, Outbound<T>> outbound = new LinkedHashMap<>();
    private final Map<UUID, Inbound> inbound = new LinkedHashMap<>();

    private SessionPhase sessionPhase = SessionPhase.DISCONNECTED;
    private UUID sessionId;
    private Instant sessionExpiresAt;
    private Instant handshakeDeadline;
    private Instant nextHandshakeAt = Instant.MIN;
    private Instant lastPeerActivity;
    private Instant nextHeartbeatAt;

    public DeliveryStateMachine(int outboundLimit, int inboundLimit, int maxAttemptsPerSession,
                                Duration retryBase, Duration idempotencyGrace, Clock clock) {
        if (outboundLimit < 1 || inboundLimit < 1 || maxAttemptsPerSession < 1
                || retryBase.isNegative() || retryBase.isZero()
                || idempotencyGrace.isNegative()) {
            throw new IllegalArgumentException("invalid delivery state-machine limits");
        }
        this.outboundLimit = outboundLimit;
        this.inboundLimit = inboundLimit;
        this.maxAttemptsPerSession = maxAttemptsPerSession;
        this.retryBase = retryBase;
        this.idempotencyGrace = idempotencyGrace;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized SessionPhase sessionPhase() {
        expireSessionIfNeeded();
        return sessionPhase;
    }

    public synchronized boolean shouldStartHandshake(boolean carrierAvailable) {
        expireSessionIfNeeded();
        return carrierAvailable && sessionPhase == SessionPhase.DISCONNECTED
                && !clock.instant().isBefore(nextHandshakeAt);
    }

    public synchronized void handshakeStarted(Duration timeout) {
        requireOpen();
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("invalid handshake timeout");
        sessionPhase = SessionPhase.HANDSHAKING;
        handshakeDeadline = safePlus(clock.instant(), timeout);
    }

    public synchronized boolean handshakeTimedOut() {
        if (sessionPhase != SessionPhase.HANDSHAKING) return false;
        if (handshakeDeadline != null && !handshakeDeadline.isAfter(clock.instant())) {
            invalidate(Duration.ZERO);
            return true;
        }
        return false;
    }

    public synchronized void activate(UUID activatedSessionId, Instant expiresAt, Duration heartbeatInterval) {
        requireOpen();
        Instant now = clock.instant();
        if (activatedSessionId == null || !expiresAt.isAfter(now)
                || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) {
            throw new IllegalArgumentException("invalid active session");
        }
        sessionId = activatedSessionId;
        sessionExpiresAt = expiresAt;
        handshakeDeadline = null;
        sessionPhase = SessionPhase.ACTIVE;
        lastPeerActivity = now;
        nextHeartbeatAt = safePlus(now, heartbeatInterval);
        outbound.values().forEach(record -> {
            record.state = DeliveryState.QUEUED;
            record.attempts = 0;
            record.dueAt = now;
        });
    }

    public synchronized void invalidate(Duration retryDelay) {
        if (sessionPhase == SessionPhase.CLOSED) return;
        if (retryDelay.isNegative()) throw new IllegalArgumentException("negative reconnect delay");
        Instant retryAt = safePlus(clock.instant(), retryDelay);
        sessionPhase = SessionPhase.DISCONNECTED;
        sessionId = null;
        sessionExpiresAt = null;
        handshakeDeadline = null;
        lastPeerActivity = null;
        nextHeartbeatAt = null;
        nextHandshakeAt = retryAt;
        outbound.values().forEach(record -> {
            record.state = DeliveryState.QUEUED;
            record.attempts = 0;
            record.dueAt = retryAt;
        });
    }

    public synchronized boolean sessionUsable() {
        expireSessionIfNeeded();
        return sessionPhase == SessionPhase.ACTIVE;
    }

    public synchronized Optional<UUID> sessionId() {
        expireSessionIfNeeded();
        return Optional.ofNullable(sessionId);
    }

    public synchronized boolean heartbeatDue() {
        expireSessionIfNeeded();
        return sessionPhase == SessionPhase.ACTIVE && nextHeartbeatAt != null
                && !nextHeartbeatAt.isAfter(clock.instant());
    }

    public synchronized void heartbeatSent(Duration interval) {
        if (sessionPhase != SessionPhase.ACTIVE || interval.isNegative() || interval.isZero()) return;
        nextHeartbeatAt = safePlus(clock.instant(), interval);
    }

    public synchronized boolean heartbeatTimedOut(Duration timeout) {
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("invalid heartbeat timeout");
        }
        expireSessionIfNeeded();
        return sessionPhase == SessionPhase.ACTIVE && lastPeerActivity != null
                && !safePlus(lastPeerActivity, timeout).isAfter(clock.instant());
    }

    public synchronized void peerActivity() {
        if (sessionPhase == SessionPhase.ACTIVE) lastPeerActivity = clock.instant();
    }

    public synchronized EnqueueResult enqueue(UUID logicalId, T payload, Instant deadline) {
        Objects.requireNonNull(logicalId, "logicalId");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(deadline, "deadline");
        if (sessionPhase == SessionPhase.CLOSED) return EnqueueResult.CLOSED;
        Instant now = clock.instant();
        purgeExpired(now);
        if (!deadline.isAfter(now)) return EnqueueResult.EXPIRED;
        if (outbound.containsKey(logicalId)) return EnqueueResult.DUPLICATE;
        if (outbound.size() >= outboundLimit) return EnqueueResult.FULL;
        outbound.put(logicalId, new Outbound<>(payload, deadline, now));
        return EnqueueResult.ACCEPTED;
    }

    public synchronized List<Attempt<T>> dueAttempts(int limit) {
        if (limit < 1) throw new IllegalArgumentException("attempt limit must be positive");
        expireSessionIfNeeded();
        Instant now = clock.instant();
        purgeExpired(now);
        if (sessionPhase != SessionPhase.ACTIVE) return List.of();
        List<Attempt<T>> due = new ArrayList<>();
        for (Map.Entry<UUID, Outbound<T>> entry : outbound.entrySet()) {
            Outbound<T> record = entry.getValue();
            if (record.attempts < maxAttemptsPerSession && !record.dueAt.isAfter(now)) {
                due.add(new Attempt<>(entry.getKey(), record.payload, record.attempts, record.deadline));
                if (due.size() == limit) break;
            }
        }
        return List.copyOf(due);
    }

    public synchronized void recordSent(UUID logicalId) {
        Outbound<T> record = outbound.get(logicalId);
        if (record == null) return;
        record.state = DeliveryState.IN_FLIGHT;
        record.attempts++;
        if (record.attempts >= maxAttemptsPerSession) {
            record.dueAt = record.deadline;
            return;
        }
        long multiplier = 1L << Math.min(record.attempts - 1, 10);
        Duration delay;
        try { delay = retryBase.multipliedBy(multiplier); }
        catch (ArithmeticException overflow) { delay = Duration.between(clock.instant(), record.deadline); }
        Instant next = safePlus(clock.instant(), delay);
        record.dueAt = next.isBefore(record.deadline) ? next : record.deadline;
    }

    public synchronized void recordTransportUnavailable(UUID logicalId) {
        Outbound<T> record = outbound.get(logicalId);
        if (record == null) return;
        record.state = DeliveryState.QUEUED;
        Instant next = safePlus(clock.instant(), retryBase);
        record.dueAt = next.isBefore(record.deadline) ? next : record.deadline;
    }

    public synchronized boolean acknowledge(UUID logicalId) {
        return outbound.remove(logicalId) != null;
    }

    public synchronized InboundDecision beginInbound(UUID logicalId, Instant logicalDeadline) {
        Objects.requireNonNull(logicalId, "logicalId");
        Objects.requireNonNull(logicalDeadline, "logicalDeadline");
        if (sessionPhase == SessionPhase.CLOSED) return InboundDecision.CLOSED;
        Instant now = clock.instant();
        purgeExpired(now);
        if (!logicalDeadline.isAfter(now)) return InboundDecision.EXPIRED;
        Inbound existing = inbound.get(logicalId);
        if (existing != null) {
            return existing.state == DeliveryState.DELIVERED
                    ? InboundDecision.DELIVERED : InboundDecision.IN_PROGRESS;
        }
        if (inbound.size() >= inboundLimit) return InboundDecision.FULL;
        inbound.put(logicalId, new Inbound(safePlus(logicalDeadline, idempotencyGrace)));
        return InboundDecision.NEW;
    }

    public synchronized boolean completeInbound(UUID logicalId) {
        purgeExpired(clock.instant());
        Inbound record = inbound.get(logicalId);
        if (record == null || record.state != DeliveryState.INBOUND_PROCESSING) return false;
        record.state = DeliveryState.DELIVERED;
        return true;
    }

    public synchronized void failInbound(UUID logicalId) {
        Inbound record = inbound.get(logicalId);
        if (record != null && record.state == DeliveryState.INBOUND_PROCESSING) inbound.remove(logicalId);
    }

    public synchronized Optional<DeliveryState> outboundState(UUID logicalId) {
        purgeExpired(clock.instant());
        Outbound<T> record = outbound.get(logicalId);
        return record == null ? Optional.empty() : Optional.of(record.state);
    }

    public synchronized int outboundSize() { purgeExpired(clock.instant()); return outbound.size(); }
    public synchronized int inboundSize() { purgeExpired(clock.instant()); return inbound.size(); }

    public synchronized int expireOutbound() {
        int before = outbound.size();
        purgeExpired(clock.instant());
        return before - outbound.size();
    }

    public synchronized int clearOutbound() {
        int removed = outbound.size();
        outbound.clear();
        return removed;
    }

    @Override public synchronized void close() {
        outbound.clear();
        inbound.clear();
        sessionPhase = SessionPhase.CLOSED;
        sessionId = null;
        sessionExpiresAt = null;
        handshakeDeadline = null;
        lastPeerActivity = null;
        nextHeartbeatAt = null;
    }

    private void expireSessionIfNeeded() {
        Instant now = clock.instant();
        // Outbound expiry is accounted by expireOutbound(). Removing it here would make
        // an owner's aggregate admission counter diverge from this bounded queue.
        purgeInboundExpired(now);
        if (sessionPhase == SessionPhase.ACTIVE && sessionExpiresAt != null && !sessionExpiresAt.isAfter(now)) {
            invalidate(Duration.ZERO);
        }
    }

    private void purgeExpired(Instant now) {
        outbound.entrySet().removeIf(entry -> !entry.getValue().deadline.isAfter(now));
        purgeInboundExpired(now);
    }

    private void purgeInboundExpired(Instant now) {
        inbound.entrySet().removeIf(entry -> !entry.getValue().retainUntil.isAfter(now));
    }

    private void requireOpen() {
        if (sessionPhase == SessionPhase.CLOSED) throw new IllegalStateException("delivery state machine is closed");
    }

    private static Instant safePlus(Instant instant, Duration duration) {
        try { return instant.plus(duration); }
        catch (RuntimeException overflow) { return Instant.MAX; }
    }
}
