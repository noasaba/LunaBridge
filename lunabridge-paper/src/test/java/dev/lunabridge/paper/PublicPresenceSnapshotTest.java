package dev.lunabridge.paper;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicPresenceSnapshotTest {
    private final UUID player = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

    @Test void visibleJoinVanishReappearAndQuitUseIdempotentSnapshotTransitions() {
        PublicPresenceSnapshot snapshot = new PublicPresenceSnapshot();

        assertTrue(snapshot.markPublic(player, "Alice"));
        assertFalse(snapshot.markPublic(player, "Alice"));
        assertEquals(1, snapshot.size());
        assertEquals("Alice", snapshot.markHidden(player)); // vanish
        assertNull(snapshot.markHidden(player));
        assertTrue(snapshot.markPublic(player, "Alice")); // reappear
        assertEquals("Alice", snapshot.markHidden(player)); // quit
        assertEquals(0, snapshot.size());
    }

    @Test void snapshotReadsOnlyStoredStringsAndSupportsSilentResync() {
        PublicPresenceSnapshot snapshot = new PublicPresenceSnapshot();
        UUID second = UUID.fromString("650e8400-e29b-41d4-a716-446655440000");
        snapshot.replace(Map.of(player, "Alice", second, "Bob"));

        assertEquals(2, snapshot.size());
        assertTrue(snapshot.playerNames().containsAll(java.util.List.of("Alice", "Bob")));
        snapshot.clear();
        assertEquals(0, snapshot.size());
    }

    @Test void defaultAndFailClosedPoliciesHaveOppositeSafeFallbacks() {
        assertTrue(new DefaultVisibilityProvider().isPublic(null));
        assertFalse(new FailClosedVisibilityProvider().isPublic(null));
    }

    @Test void integrationFilteringCanSilentlyRemoveAndRestorePublicPresence() {
        PublicPresenceSnapshot snapshot = new PublicPresenceSnapshot();
        PublicVisibilityProvider integration = ignored -> false;

        if (integration.isPublic(null)) snapshot.markPublic(player, "Alice");
        assertEquals(0, snapshot.size());

        integration = ignored -> true;
        if (integration.isPublic(null)) snapshot.markPublic(player, "Alice");
        assertEquals(java.util.List.of("Alice"), snapshot.playerNames());
    }
}
