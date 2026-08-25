package dev.lunabridge.core.delivery;

/** Observable logical-delivery states. Secure-frame identity is deliberately separate. */
public enum DeliveryState {
    QUEUED,
    IN_FLIGHT,
    INBOUND_PROCESSING,
    DELIVERED
}
