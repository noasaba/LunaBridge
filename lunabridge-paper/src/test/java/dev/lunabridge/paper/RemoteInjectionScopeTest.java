package dev.lunabridge.paper;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RemoteInjectionScopeTest {
    @Test void scopeIsAlwaysRemovedAndDoesNotLeakToNormalLunaChatObservation() {
        assertFalse(RemoteInjectionScope.active());
        RemoteInjectionScope.run(() -> assertTrue(RemoteInjectionScope.active()));
        assertFalse(RemoteInjectionScope.active());
        assertThrows(IllegalStateException.class, () -> RemoteInjectionScope.run(() -> RemoteInjectionScope.run(() -> { })));
        assertFalse(RemoteInjectionScope.active());
    }
}
