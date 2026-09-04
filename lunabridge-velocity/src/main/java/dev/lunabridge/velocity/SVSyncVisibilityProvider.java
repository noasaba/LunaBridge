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
            Optional<Object> instance = proxy.getPluginManager().getPlugin("svsync")
                    .flatMap(container -> container.getInstance());
            if (instance.isEmpty()) return Optional.empty();
            if (!(instance.get() instanceof SVSyncApi api)) {
                logger.warn("SVSync is installed but does not expose the expected SVSyncApi; vanish filtering is disabled.");
                return Optional.empty();
            }
            return Optional.of(new SVSyncVisibilityProvider(api, logger));
        } catch (LinkageError failure) {
            logger.warn("SVSync API is unavailable; vanish filtering is disabled.", failure);
            return Optional.empty();
        }
    }

    boolean isPublic(UUID playerId) {
        try {
            return isPublic(api, playerId);
        } catch (RuntimeException failure) {
            if (lookupFailureLogged.compareAndSet(false, true)) {
                logger.warn("SVSync state lookup failed; treating unavailable state as non-public.", failure);
            }
            return false;
        }
    }

    static boolean isPublic(SVSyncApi api, UUID playerId) {
        // With SVSync installed, unknown state must not disclose a player during Paper-to-Velocity sync.
        return api.hasState(playerId) && !api.isVanished(playerId);
    }
}
