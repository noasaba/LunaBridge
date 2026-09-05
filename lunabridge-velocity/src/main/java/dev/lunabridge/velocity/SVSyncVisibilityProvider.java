package dev.lunabridge.velocity;

import com.noasaba.svsync.api.SVSyncApi;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional, read-only public-presence adapter for SVSync's Velocity runtime state. */
final class SVSyncVisibilityProvider {
    private final SVSyncApi api;
    private final Logger logger;
    private final AtomicBoolean lookupFailureLogged = new AtomicBoolean();

    private SVSyncVisibilityProvider(SVSyncApi api, Logger logger) {
        this.api = api;
        this.logger = logger;
    }

    static Optional<SVSyncVisibilityProvider> find(ProxyServer proxy, Logger logger) {
        try {
            var container = proxy.getPluginManager().getPlugin("svsync");
            if (container.isEmpty()) return Optional.empty();
            Optional<?> instance = container.orElseThrow().getInstance();
            if (instance.isEmpty()) {
                logger.warn("SVSync is installed but has no plugin instance; public presence is fail-closed.");
                return Optional.of(new SVSyncVisibilityProvider(null, logger));
            }
            if (!(instance.get() instanceof SVSyncApi api)) {
                logger.warn("SVSync is installed but does not expose the expected SVSyncApi; public presence is fail-closed.");
                return Optional.of(new SVSyncVisibilityProvider(null, logger));
            }
            return Optional.of(new SVSyncVisibilityProvider(api, logger));
        } catch (LinkageError failure) {
            logger.warn("SVSync API is unavailable; public presence is fail-closed.", failure);
            return Optional.of(new SVSyncVisibilityProvider(null, logger));
        }
    }

    Visibility visibility(UUID playerId) {
        if (api == null) return Visibility.UNKNOWN;
        try {
            if (!api.hasState(playerId)) return Visibility.UNKNOWN;
            return api.isVanished(playerId) ? Visibility.HIDDEN : Visibility.PUBLIC;
        } catch (RuntimeException failure) {
            if (lookupFailureLogged.compareAndSet(false, true)) {
                logger.warn("SVSync state lookup failed; treating unavailable state as non-public.", failure);
            }
            return Visibility.UNKNOWN;
        }
    }

    static boolean isPublic(SVSyncApi api, UUID playerId) {
        // With SVSync installed, unknown state must not disclose a player during Paper-to-Velocity sync.
        return api.hasState(playerId) && !api.isVanished(playerId);
    }

    enum Visibility { PUBLIC, HIDDEN, UNKNOWN }
}
