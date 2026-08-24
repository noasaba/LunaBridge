package dev.lunabridge.paper;

/** Synchronous LunaChat reinjection emits its normal event; this scope is the first loop guard. */
final class RemoteInjectionScope {
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);
    private RemoteInjectionScope() { }
    static boolean active() { return ACTIVE.get(); }
    static void run(Runnable action) {
        if (active()) throw new IllegalStateException("nested remote injection");
        ACTIVE.set(true);
        try { action.run(); } finally { ACTIVE.remove(); }
    }
}
