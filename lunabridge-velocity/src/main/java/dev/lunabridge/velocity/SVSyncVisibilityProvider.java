package dev.lunabridge.velocity;

import com.noasaba.svsync.api.SVSyncApi;
import com.noasaba.svsync.api.SVSyncSubscription;
import com.noasaba.svsync.api.VisibilityChange;
import com.velocitypowered.api.proxy.ProxyServer;
import org.slf4j.Logger;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Optional, read-only public-presence adapter for SVSync's Velocity runtime state. */
final class SVSyncVisibilityProvider implements AutoCloseable {
    private final SVSyncApi api;
    private final Logger logger;
    private final SVSyncSubscription subscription;
    private final AtomicBoolean lookupFailureLogged = new AtomicBoolean();

    SVSyncVisibilityProvider(SVSyncApi api, Logger logger, Consumer<VisibilityChange> listener) {
        this.api = api;
        this.logger = logger;
        this.subscription = api == null ? null : api.addVisibilityListener(listener::accept);
    }

    static Optional<SVSyncVisibilityProvider> find(ProxyServer proxy, Logger logger,
                                                   Consumer<VisibilityChange> listener) {
        try {
            var container = proxy.getPluginManager().getPlugin("svsync");
            if (container.isEmpty()) return Optional.empty();
            Optional<?> instance = container.orElseThrow().getInstance();
            if (instance.isEmpty()) {
                logger.warn("SVSync is installed but has no plugin instance; public presence is fail-closed.");
                return Optional.of(new SVSyncVisibilityProvider(null, logger, listener));
            }
            if (!(instance.get() instanceof SVSyncApi api)) {
                logger.warn("SVSync is installed but does not expose the expected SVSyncApi; public presence is fail-closed.");
                return Optional.of(new SVSyncVisibilityProvider(null, logger, listener));
            }
            return Optional.of(new SVSyncVisibilityProvider(api, logger, listener));
        } catch (LinkageError | RuntimeException failure) {
            logger.warn("SVSync API is unavailable; public presence is fail-closed.", failure);
            return Optional.of(new SVSyncVisibilityProvider(null, logger, listener));
        }
    }

    Visibility visibility(UUID playerId) {
        if (api == null) return Visibility.UNKNOWN;
        try {
            return map(api.getVisibility(playerId));
        } catch (RuntimeException failure) {
            if (lookupFailureLogged.compareAndSet(false, true)) {
                logger.warn("SVSync state lookup failed; treating unavailable state as non-public.", failure);
            }
            return Visibility.UNKNOWN;
        }
    }

    static boolean isPublic(SVSyncApi api, UUID playerId) {
        return api.getVisibility(playerId) != com.noasaba.svsync.api.Visibility.HIDDEN;
    }

    private static Visibility map(com.noasaba.svsync.api.Visibility visibility) {
        return visibility == com.noasaba.svsync.api.Visibility.HIDDEN ? Visibility.HIDDEN : Visibility.PUBLIC;
    }

    @Override public void close() {
        if (subscription != null) subscription.close();
    }

    enum Visibility { PUBLIC, HIDDEN, UNKNOWN }
}
