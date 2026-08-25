package dev.lunabridge.discord;

import com.github.ucchyocean.lunachat.api.ApiVersion;
import com.github.ucchyocean.lunachat.api.Capability;
import com.github.ucchyocean.lunachat.api.ChannelQueryService;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.MessageGateway;
import com.github.ucchyocean.lunachat.api.NetworkStatusService;
import com.github.ucchyocean.lunachat.api.RuntimeRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DiscordContractTest {
    @TempDir Path temporaryDirectory;

    @Test void frozenApiV1RoleAndCapabilitiesAreRequired() {
        LunaChatIntegrationApi api = api(RuntimeRole.STANDALONE_AUTHORITY,
                EnumSet.of(Capability.QUERY_CHANNELS, Capability.OBSERVE_ACCEPTED_MESSAGES,
                        Capability.PUBLISH_EXTERNAL_MESSAGES));
        assertDoesNotThrow(() -> AuthorityValidation.require(api, RuntimeRole.STANDALONE_AUTHORITY));
        assertThrows(IllegalStateException.class, () -> AuthorityValidation.require(api, RuntimeRole.NETWORK_AUTHORITY));
        assertThrows(IllegalStateException.class, () -> AuthorityValidation.require(
                api(RuntimeRole.NETWORK_EDGE, api.capabilities()), RuntimeRole.STANDALONE_AUTHORITY));
        assertThrows(IllegalStateException.class, () -> AuthorityValidation.require(
                api(RuntimeRole.STANDALONE_AUTHORITY, Set.of(Capability.OBSERVE_ACCEPTED_MESSAGES)),
                RuntimeRole.STANDALONE_AUTHORITY));
    }

    @Test void receiptCacheExpiresAndNeverGrowsPastCapacity() {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        var clock = Clock.fixed(now, ZoneOffset.UTC);
        MessageReceiptCache cache = new MessageReceiptCache(2, Duration.ofSeconds(10), clock);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID third = UUID.randomUUID();
        assertTrue(cache.markIfNew(first));
        assertFalse(cache.markIfNew(first));
        assertTrue(cache.markIfNew(second));
        assertFalse(cache.markIfNew(third));
        assertEquals(2, cache.size());
    }

    @Test void discordTextDoesNotSplitSurrogatePairOrAllowMentions() {
        String fitted = DiscordText.fit("a".repeat(1_998) + "😀tail");
        assertTrue(fitted.length() <= DiscordText.MAX_LENGTH);
        assertTrue(fitted.endsWith("…"));
        assertFalse(Character.isHighSurrogate(fitted.charAt(fitted.length() - 2)));
        assertEquals("@\u200Beveryone", DiscordText.suppressMentions("@everyone"));
    }

    @Test void discordTextRemovesMinecraftFormattingAndPreservesUnicode() {
        assertEquals("aaa (あああ)", DiscordText.stripMinecraftLegacyFormatting("aaa §6(あああ)"));
        assertEquals("Bold reset", DiscordText.stripMinecraftLegacyFormatting("§lBold §rreset"));
        assertEquals("Hex 😀", DiscordText.stripMinecraftLegacyFormatting("§x§1§2§A§b§F§0Hex 😀"));
        assertEquals("noa_berry", DiscordText.stripMinecraftLegacyFormatting("noa_berry"));
        assertEquals("literal §z", DiscordText.stripMinecraftLegacyFormatting("literal §z"));
    }

    @Test void minecraftFormattingRemovalComposesWithMentionSuppressionAndLengthLimit() {
        String safe = DiscordText.fit(DiscordText.suppressMentions(
                DiscordText.stripMinecraftLegacyFormatting("§c@everyone " + "a".repeat(2_100))));
        assertTrue(safe.startsWith("@\u200Beveryone "));
        assertTrue(safe.length() <= DiscordText.MAX_LENGTH);
        assertTrue(safe.endsWith("…"));
    }

    @Test void minecraftRelaySanitizesPresentationWithoutChangingAcceptedMessage() {
        Instant created = Instant.parse("2026-08-25T00:00:00Z");
        var message = new com.github.ucchyocean.lunachat.api.AcceptedMessage(
                UUID.randomUUID(),
                new com.github.ucchyocean.lunachat.api.ChannelId("550e8400-e29b-41d4-a716-446655440000"),
                "§aglobal",
                new com.github.ucchyocean.lunachat.api.MessageOrigin(
                        com.github.ucchyocean.lunachat.api.OriginKind.MINECRAFT,
                        "lunachat.minecraft", UUID.randomUUID().toString()),
                new com.github.ucchyocean.lunachat.api.MessageAuthor.Player(
                        UUID.randomUUID(), "noa_berry", "§bnoa_berry"),
                "backend", "aaa §6(あああ)", created, created.plus(Duration.ofMinutes(5)));

        assertEquals("[global] noa_berry: aaa (あああ)", DiscordConnector.minecraftRelayText(message));
        assertEquals("§aglobal", message.channelName());
        assertEquals("aaa §6(あああ)", message.content());
        assertEquals("§bnoa_berry", ((com.github.ucchyocean.lunachat.api.MessageAuthor.Player) message.author()).displayName());
    }

    @Test void officialApiRecordConstructionUsesDiscordIdentityContract() {
        var channel = new com.github.ucchyocean.lunachat.api.ChannelId("550e8400-e29b-41d4-a716-446655440000");
        var identity = new com.github.ucchyocean.lunachat.api.ExternalMessageIdentity("lunabridge:discord", "123");
        var author = new com.github.ucchyocean.lunachat.api.MessageAuthor.External("lunabridge:discord", "456", "Alice");
        var request = new com.github.ucchyocean.lunachat.api.ExternalMessageRequest(channel, identity, author,
                "hello", Instant.parse("2026-01-01T00:00:00Z"), Duration.ofMinutes(5));
        assertEquals(identity.namespace(), author.namespace());
        assertEquals("123", request.identity().value());
    }

    @Test void tokenFileTakesPrecedenceAndRejectsMultipleLines() throws Exception {
        Path token = temporaryDirectory.resolve("discord.token");
        Files.writeString(token, "file-token\n");
        assertEquals("file-token", DiscordToken.resolve("inline-token", "discord.token", temporaryDirectory));
        Files.writeString(token, "first\nsecond\n");
        assertThrows(java.io.IOException.class,
                () -> DiscordToken.resolve("inline-token", "discord.token", temporaryDirectory));
    }

    @Test void setupResolvesNamesOnceAndRequiresExternalPublishing() {
        var enabled = new com.github.ucchyocean.lunachat.api.ChannelDescriptor(
                new com.github.ucchyocean.lunachat.api.ChannelId("550e8400-e29b-41d4-a716-446655440000"),
                "global", Set.of("g"), true);
        var disabled = new com.github.ucchyocean.lunachat.api.ChannelDescriptor(
                new com.github.ucchyocean.lunachat.api.ChannelId("650e8400-e29b-41d4-a716-446655440000"),
                "staff", Set.of(), false);
        LunaChatIntegrationApi api = apiWithChannels(enabled, disabled);
        assertEquals(enabled, BridgeAdministration.resolveSetup(api, "1307767610976243722", "g"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeAdministration.resolveSetup(api, "1307767610976243722", "staff"));
        assertThrows(IllegalArgumentException.class,
                () -> BridgeAdministration.resolveSetup(api, "not-a-snowflake", "global"));
    }

    @Test void nonRetryablePublishResultIsTerminal() {
        AtomicInteger attempts = new AtomicInteger();
        var executor = Executors.newSingleThreadScheduledExecutor();
        var queue = new BoundedPublishRetryQueue(1, 5, request -> {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture(
                    com.github.ucchyocean.lunachat.api.ExternalPublishResult.rejected(
                            com.github.ucchyocean.lunachat.api.PublishStatus.FORBIDDEN, true, "forbidden"));
        }, executor, Clock.systemUTC());
        var request = new com.github.ucchyocean.lunachat.api.ExternalMessageRequest(
                new com.github.ucchyocean.lunachat.api.ChannelId("550e8400-e29b-41d4-a716-446655440000"),
                new com.github.ucchyocean.lunachat.api.ExternalMessageIdentity("lunabridge:discord", "123"),
                new com.github.ucchyocean.lunachat.api.MessageAuthor.External("lunabridge:discord", "456", "Alice"),
                "hello", Instant.now(), Duration.ofMinutes(5));
        assertTrue(queue.submit(request));
        assertEquals(1, attempts.get());
        assertEquals(0, queue.pendingCount());
        queue.close();
    }

    private static LunaChatIntegrationApi api(RuntimeRole role, Set<Capability> capabilities) {
        return new LunaChatIntegrationApi() {
            @Override public ApiVersion apiVersion() { return new ApiVersion(1, 0, 0); }
            @Override public RuntimeRole runtimeRole() { return role; }
            @Override public Set<Capability> capabilities() { return capabilities; }
            @Override public ChannelQueryService channels() { return null; }
            @Override public MessageGateway messages() { return null; }
            @Override public NetworkStatusService networkStatus() { return null; }
        };
    }

    private static LunaChatIntegrationApi apiWithChannels(
            com.github.ucchyocean.lunachat.api.ChannelDescriptor... descriptors) {
        return new LunaChatIntegrationApi() {
            @Override public ApiVersion apiVersion() { return new ApiVersion(1, 0, 0); }
            @Override public RuntimeRole runtimeRole() { return RuntimeRole.NETWORK_AUTHORITY; }
            @Override public Set<Capability> capabilities() { return EnumSet.allOf(Capability.class); }
            @Override public ChannelQueryService channels() {
                return new ChannelQueryService() {
                    @Override public java.util.Optional<com.github.ucchyocean.lunachat.api.ChannelDescriptor> find(
                            com.github.ucchyocean.lunachat.api.ChannelId id) {
                        return java.util.Arrays.stream(descriptors).filter(value -> value.id().equals(id)).findFirst();
                    }
                    @Override public java.util.Optional<com.github.ucchyocean.lunachat.api.ChannelDescriptor> findByNameOrAlias(
                            String name) {
                        return java.util.Arrays.stream(descriptors)
                                .filter(value -> value.name().equals(name) || value.aliases().contains(name)).findFirst();
                    }
                    @Override public com.github.ucchyocean.lunachat.api.ChannelPage listVisibleToIntegration(
                            com.github.ucchyocean.lunachat.api.ChannelPageRequest request) { return null; }
                };
            }
            @Override public MessageGateway messages() { return null; }
            @Override public NetworkStatusService networkStatus() { return null; }
        };
    }
}
