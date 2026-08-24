package dev.lunabridge.core.delivery;

/** Honest state machine: client rendering is intentionally outside this contract. */
public enum DeliveryState {
    ACCEPTED,
    FORWARDED,
    REMOTE_ACCEPTED,
    DELIVERED,
    FAILED,
    EXPIRED,
    INDETERMINATE
}
