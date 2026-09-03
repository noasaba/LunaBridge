package dev.lunabridge.paper;

import org.bukkit.entity.Player;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Optional SuperVanish/PremiumVanish adapter using only their public API. */
public final class SuperVanishVisibilityProvider implements PublicVisibilityProvider {
    private final Method isInvisible;

    public SuperVanishVisibilityProvider() {
        try {
            Class<?> api = Class.forName("de.myzelyam.api.vanish.VanishAPI");
            isInvisible = api.getMethod("isInvisible", Player.class);
        } catch (ReflectiveOperationException | LinkageError failure) {
            throw new IllegalStateException("SuperVanish public API is unavailable", failure);
        }
    }

    @Override public boolean isPublic(Player player) {
        try {
            return !((Boolean) isInvisible.invoke(null, player));
        } catch (IllegalAccessException | InvocationTargetException | ClassCastException | LinkageError failure) {
            throw new IllegalStateException("SuperVanish visibility lookup failed", failure);
        }
    }
}
