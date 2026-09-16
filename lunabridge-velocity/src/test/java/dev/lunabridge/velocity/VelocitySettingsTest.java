package dev.lunabridge.velocity;

import com.noasaba.svsync.api.SVSyncApi;
import com.noasaba.svsync.api.Visibility;
import com.noasaba.svsync.api.VisibilityChange;
import com.velocitypowered.api.network.ListenerType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VelocitySettingsTest {
    @TempDir Path directory;

    @Test void olderSchemaMigratesAdditivelyAndTokenFileWins() throws Exception {
        Files.writeString(directory.resolve("token"), "file-token\n");
        Files.writeString(directory.resolve("config.properties"), """
                config-version=2
                discord.token=inline-token
                discord.token-file=token
                discord.channels.1307767610976243722.lunachat-channel-id=550e8400-e29b-41d4-a716-446655440000
                """);
        VelocitySettings settings = VelocitySettings.load(directory);
        assertEquals("file-token", settings.discord.token());
        assertEquals("550e8400-e29b-41d4-a716-446655440000",
                settings.discord.discordChannelToLunaChatChannelId().get("1307767610976243722"));
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("config-version=5"));
        assertEquals("Discord:{username}", settings.discord.option("discord.external-display-name-format", ""));
        assertTrue(persisted.contains("discord.minecraft-chat-format="));
        assertEquals("[{channel}] {username}: {message}{japanized}",
                settings.discord.option("discord.minecraft-chat-format", ""));
        assertTrue(Files.exists(directory.resolve("config.properties.v2.bak")));
        VelocitySettings.load(directory);
        assertEquals(persisted, Files.readString(directory.resolve("config.properties")));
    }

    @Test void futureSchemaIsRejectedWithoutChangingOriginal() throws Exception {
        Path config = directory.resolve("config.properties");
        String original = "config-version=999\ndiscord.token=keep-me\n";
        Files.writeString(config, original);
        assertThrows(IllegalStateException.class, () -> VelocitySettings.load(directory));
        assertEquals(original, Files.readString(config));
    }

    @Test void legacyKeysAreCommentedAndBackupsNeverOverwrite() throws Exception {
        Path config = directory.resolve("config.properties");
        Files.writeString(config, """
                # operator note
                network.secret=do-not-log
                server.id=legacy
                discord.token=keep-me
                """);
        VelocitySettings.load(directory);
        String migrated = Files.readString(config);
        assertTrue(migrated.contains("# deprecated/removed in config-version 5"));
        assertTrue(migrated.contains("# network.secret=do-not-log"));
        assertTrue(migrated.contains("# operator note"));
        assertTrue(Files.exists(directory.resolve("config.properties.v0.bak")));

        Files.writeString(config, "network.secret=second\ndiscord.token=keep-me\n");
        VelocitySettings.load(directory);
        assertTrue(Files.exists(directory.resolve("config.properties.v0.bak.1")));
        assertTrue(Files.readString(directory.resolve("config.properties.v0.bak")).contains("do-not-log"));
    }

    @Test void setupPersistsStableIdWithoutRemovingExistingConfiguration() throws Exception {
        Files.writeString(directory.resolve("config.properties"), "config-version=3\ndiscord.token=keep-me\n");
        VelocitySettings.saveMapping(directory, "1307767610976243722",
                "550e8400-e29b-41d4-a716-446655440000");
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("discord.token=keep-me"));
        assertTrue(persisted.contains("config-version=5"));
        assertTrue(persisted.contains("discord.channels.1307767610976243722.lunachat-channel-id="
                + "550e8400-e29b-41d4-a716-446655440000"));
    }

    @Test void mappingUpdatesPreserveCommentsAndCanBeRemoved() throws Exception {
        Files.writeString(directory.resolve("config.properties"), """
                # Keep this operator note
                config-version=5
                discord.token=keep-me
                discord.minecraft-chat-format=[{channel}] {username}: {message}{japanized}
                discord.external-display-name-format=Discord:{username}
                """);
        VelocitySettings.saveMapping(directory, "1307767610976243722",
                "550e8400-e29b-41d4-a716-446655440000");
        assertTrue(Files.readString(directory.resolve("config.properties")).contains("# Keep this operator note"));
        VelocitySettings.removeMapping(directory, "1307767610976243722");
        String persisted = Files.readString(directory.resolve("config.properties"));
        assertTrue(persisted.contains("# Keep this operator note"));
        assertTrue(!persisted.contains("discord.channels.1307767610976243722"));
    }

    @Test void svsyncMakesOnlyVanishedPlayersNonPublic() {
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertTrue(SVSyncVisibilityProvider.isPublic(state(false, false), playerId));
        assertTrue(!SVSyncVisibilityProvider.isPublic(state(true, true), playerId));
        assertTrue(SVSyncVisibilityProvider.isPublic(state(true, false), playerId));
    }

    @Test void svsyncExplicitReappearIsTheOnlyVisibilityChangeThatAnnouncesLogin() {
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertTrue(LunaBridgeVelocityPlugin.shouldNotifyReappear(new VisibilityChange(
                playerId, Visibility.HIDDEN, Visibility.PUBLIC, true, "main", 1)));
        assertTrue(!LunaBridgeVelocityPlugin.shouldNotifyReappear(new VisibilityChange(
                playerId, Visibility.UNKNOWN, Visibility.PUBLIC, true, "main", 2)));
        assertTrue(!LunaBridgeVelocityPlugin.shouldNotifyReappear(new VisibilityChange(
                playerId, Visibility.HIDDEN, Visibility.PUBLIC, false, "main", 3)));
    }

    @Test void svsyncVisibilitySubscriptionIsClosed() {
        AtomicBoolean closed = new AtomicBoolean();
        SVSyncApi api = new SVSyncApi() {
            @Override public boolean hasState(UUID playerId) { return true; }
            @Override public boolean isVanished(UUID playerId) { return false; }
            @Override public Visibility getVisibility(UUID playerId) { return Visibility.PUBLIC; }
            @Override public com.noasaba.svsync.api.SVSyncSubscription addVisibilityListener(
                    com.noasaba.svsync.api.VisibilityListener listener) {
                return () -> closed.set(true);
            }
        };
        new SVSyncVisibilityProvider(api, org.slf4j.LoggerFactory.getLogger("test"), ignored -> { }).close();
        assertTrue(closed.get());
    }

    @Test void administrationAllowsConsoleAndAuthorizedPlayersOnly() {
        assertTrue(LunaBridgeVelocityPlugin.isAdministrationAuthorized(true, false));
        assertTrue(LunaBridgeVelocityPlugin.isAdministrationAuthorized(false, true));
        assertTrue(!LunaBridgeVelocityPlugin.isAdministrationAuthorized(false, false));
    }

    @Test void disconnectUsesLastKnownVisibilityWhenSVSyncAlreadyRemovedState() {
        assertTrue(LunaBridgeVelocityPlugin.isPublicAtDisconnect(
                SVSyncVisibilityProvider.Visibility.UNKNOWN, true));
        assertTrue(!LunaBridgeVelocityPlugin.isPublicAtDisconnect(
                SVSyncVisibilityProvider.Visibility.UNKNOWN, false));
        assertTrue(!LunaBridgeVelocityPlugin.isPublicAtDisconnect(
                SVSyncVisibilityProvider.Visibility.HIDDEN, true));
    }

    @Test void discordLifecycleIsOwnedOnlyByTheMinecraftListener() {
        assertTrue(LunaBridgeVelocityPlugin.ownsDiscordLifecycle(ListenerType.MINECRAFT));
        assertTrue(!LunaBridgeVelocityPlugin.ownsDiscordLifecycle(ListenerType.QUERY));
    }

    @Test void seenPlayerStoreDistinguishesFirstLoginWithoutReplacingNormalLogin() throws Exception {
        SeenPlayerStore store = new SeenPlayerStore(directory);
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        assertTrue(store.markFirst(playerId));
        assertTrue(!store.markFirst(playerId));
        assertEquals(java.util.List.of("login", "first-login"),
                LunaBridgeVelocityPlugin.loginNotificationTypes(true));
        assertEquals(java.util.List.of("login"),
                LunaBridgeVelocityPlugin.loginNotificationTypes(false));
    }

    private static SVSyncApi state(boolean hasState, boolean vanished) {
        return new SVSyncApi() {
            @Override public boolean hasState(UUID playerId) { return hasState; }
            @Override public boolean isVanished(UUID playerId) { return vanished; }
            @Override public com.noasaba.svsync.api.Visibility getVisibility(UUID playerId) {
                return !hasState ? com.noasaba.svsync.api.Visibility.UNKNOWN
                        : vanished ? com.noasaba.svsync.api.Visibility.HIDDEN
                        : com.noasaba.svsync.api.Visibility.PUBLIC;
            }
            @Override public com.noasaba.svsync.api.SVSyncSubscription addVisibilityListener(
                    com.noasaba.svsync.api.VisibilityListener listener) {
                return () -> { };
            }
        };
    }
}
