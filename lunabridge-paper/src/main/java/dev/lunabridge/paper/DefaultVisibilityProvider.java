package dev.lunabridge.paper;

import org.bukkit.entity.Player;

/** Visibility policy used when no supported vanish plugin is enabled. */
public final class DefaultVisibilityProvider implements PublicVisibilityProvider {
    @Override public boolean isPublic(Player player) { return true; }
}
