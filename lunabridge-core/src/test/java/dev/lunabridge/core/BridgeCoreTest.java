package dev.lunabridge.core;

import dev.lunabridge.core.config.ConfigMigration;
import dev.lunabridge.core.model.BridgeChannelMapping;
import dev.lunabridge.core.model.BridgeMessage;
import dev.lunabridge.core.model.BridgeOrigin;
import dev.lunabridge.core.protocol.BridgeMessageCodec;
import dev.lunabridge.core.protocol.ChannelManifestCodec;
import dev.lunabridge.core.protocol.ProtocolException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BridgeCoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void messageWireRoundTripAndStrictTrailingByteHandling() throws Exception {
        BridgeMessage message = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.MINECRAFT,
                "global", "Global", UUID.randomUUID(), "Alice", "hello", "lobby", NOW, NOW.plusSeconds(10));
        byte[] encoded = BridgeMessageCodec.encode(message);
        assertEquals(message, BridgeMessageCodec.decode(encoded));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertEquals(ProtocolException.Code.MALFORMED,
                assertThrows(ProtocolException.class, () -> BridgeMessageCodec.decode(trailing)).code());
    }

    @Test void messageWireRejectsMalformedUtf8() {
        UUID zero = new UUID(0, 0);
        BridgeMessage message = new BridgeMessage(zero, BridgeOrigin.MINECRAFT,
                "a", "Global", zero, "Alice", "hello", "lobby", NOW, NOW.plusSeconds(10));
        byte[] encoded = BridgeMessageCodec.encode(message);
        encoded[21] = (byte) 0xC0; // first bridge-key byte after the v2 fixed header and its length.
        assertEquals(ProtocolException.Code.MALFORMED,
                assertThrows(ProtocolException.class, () -> BridgeMessageCodec.decode(encoded)).code());
    }

    @Test void logicalLifetimeAndFutureClockSkewCannotPinIdempotencyCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new BridgeMessage(UUID.randomUUID(), BridgeOrigin.DISCORD,
                "global", "Global", null, "User", "hello", "discord", NOW, NOW.plusSeconds(11)));
        BridgeMessage future = new BridgeMessage(UUID.randomUUID(), BridgeOrigin.DISCORD,
                "global", "Global", null, "User", "hello", "discord",
                NOW.plus(Duration.ofSeconds(31)), NOW.plus(Duration.ofSeconds(40)));
        assertEquals(ProtocolException.Code.EXPIRED, assertThrows(ProtocolException.class,
                () -> BridgeMessageCodec.requireCurrent(future, CLOCK)).code());
    }

    @Test void mappingsAreExplicitAndOneToOne() throws Exception {
        BridgeChannelMapping mapping = new BridgeChannelMapping(Map.of("global", "Global"));
        assertEquals("global", mapping.bridgeKeyForLunaChannel("Global").orElseThrow());
        assertEquals("Global", mapping.lunaChannelForBridgeKey("global").orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new BridgeChannelMapping(Map.of("a", "Global", "b", "Global")));
        assertThrows(IllegalArgumentException.class, () -> new BridgeChannelMapping(Map.of("Global", "Global")));
        assertThrows(IllegalArgumentException.class, () -> new BridgeChannelMapping(Map.of("global", "界".repeat(43))));
        assertEquals(Map.of("global", "Global"), ChannelManifestCodec.decode(
                ChannelManifestCodec.encode(mapping.asBridgeKeyToLunaName())));
        assertDoesNotThrow(() -> ChannelManifestCodec.requireCompatible(
                Map.of("global", "Global"), Map.of("staff", "Staff")));
        assertEquals(ProtocolException.Code.AUTHENTICATION_FAILED, assertThrows(ProtocolException.class,
                () -> ChannelManifestCodec.requireCompatible(
                        Map.of("global", "Global"), Map.of("global", "Different"))).code());
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
