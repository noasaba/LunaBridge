package dev.lunabridge.core.delivery;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeliveryReceipt(UUID messageId, DeliveryState state, int attempt, Instant observedAt, String detail) {
    public DeliveryReceipt {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(state, "state");
        if (attempt < 0) throw new IllegalArgumentException("attempt is negative");
        Objects.requireNonNull(observedAt, "observedAt");
        detail = detail == null ? "" : detail;
        if (detail.length() > 160) throw new IllegalArgumentException("detail is too long");
    }

    public boolean terminal() {
        return switch (state) {
            case DELIVERED, FAILED, EXPIRED, INDETERMINATE -> true;
            default -> false;
        };
    }

    public DeliveryReceipt transition(DeliveryState next, int nextAttempt, Instant at, String nextDetail) {
        if (terminal()) throw new IllegalStateException("terminal receipts cannot transition");
        if (nextAttempt < attempt || at.isBefore(observedAt)) throw new IllegalArgumentException("non-monotonic receipt");
        return new DeliveryReceipt(messageId, next, nextAttempt, at, nextDetail);
    }
}
