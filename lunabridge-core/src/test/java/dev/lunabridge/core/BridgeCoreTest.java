package dev.lunabridge.core;

import dev.lunabridge.core.config.ConfigMigration;
import dev.lunabridge.core.delivery.BoundedDedupCache;
import dev.lunabridge.core.delivery.BoundedRetryQueue;
import dev.lunabridge.core.model.BridgeChannelMapping;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import dev.lunabridge.core.protocol.BridgeMessageCodec;
import dev.lunabridge.core.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BridgeCoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void messageWireRoundTripAndStrictTrailingByteHandling() throws Exception {
        BridgeMessage message = new BridgeMessage(UUID.randomUUID(), UUID.randomUUID(), BridgeOrigin.MINECRAFT,
                "global", "Global", UUID.randomUUID(), "Alice", "hello", "lobby", NOW, NOW.plusSeconds(10));
        byte[] encoded = BridgeMessageCodec.encode(message);
        assertEquals(message, BridgeMessageCodec.decode(encoded));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertEquals(ProtocolException.Code.MALFORMED,
                assertThrows(ProtocolException.class, () -> BridgeMessageCodec.decode(trailing)).code());
    }

    @Test void dedupDoesNotEvictLiveRecordsToAdmitMoreWork() {
        BoundedDedupCache cache = new BoundedDedupCache(1, Duration.ofHours(1), CLOCK);
        UUID first = UUID.randomUUID();
        assertEquals(BoundedDedupCache.Result.NEW, cache.admit(first));
        assertEquals(BoundedDedupCache.Result.DUPLICATE, cache.admit(first));
        assertEquals(BoundedDedupCache.Result.FULL, cache.admit(UUID.randomUUID()));
    }

    @Test void queueIsBoundedAndDeadlineBound() {
        BoundedRetryQueue<String> queue = new BoundedRetryQueue<>(1, 2, CLOCK);
        assertEquals(BoundedRetryQueue.Offer.ACCEPTED, queue.offer(UUID.randomUUID(), "one", NOW.plusSeconds(3)));
        assertEquals(BoundedRetryQueue.Offer.FULL, queue.offer(UUID.randomUUID(), "two", NOW.plusSeconds(3)));
        assertEquals(BoundedRetryQueue.Offer.EXPIRED, queue.offer(UUID.randomUUID(), "late", NOW));
    }

    @Test void mappingsAreExplicitAndOneToOne() {
        BridgeChannelMapping mapping = new BridgeChannelMapping(Map.of("global", "Global"));
        assertEquals("global", mapping.bridgeKeyForLunaChannel("Global").orElseThrow());
        assertEquals("Global", mapping.lunaChannelForBridgeKey("global").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new BridgeChannelMapping(Map.of("a", "Global", "b", "Global")));
    }

    @Test void migrationPreservesExistingValuesAndDoesNotDowngradeFutureSchema() {
        ConfigMigration.Result migrated = ConfigMigration.migrate(Map.of("config-version", "0", "network.shared-pass", "keep-me"));
        assertTrue(migrated.changed());
        assertEquals("keep-me", migrated.values().get("network.shared-pass"));
        assertEquals("1", migrated.values().get("config-version"));
        ConfigMigration.Result future = ConfigMigration.migrate(Map.of("config-version", "999"));
        assertTrue(future.newerSchema());
        assertFalse(future.changed());
    }
}
