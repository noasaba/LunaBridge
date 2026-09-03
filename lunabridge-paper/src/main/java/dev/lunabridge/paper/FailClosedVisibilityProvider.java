package dev.lunabridge.paper;

import org.bukkit.entity.Player;

/** Privacy-preserving policy used while an installed vanish integration is unavailable. */
public final class FailClosedVisibilityProvider implements PublicVisibilityProvider {
    @Override public boolean isPublic(Player player) { return false; }
}
