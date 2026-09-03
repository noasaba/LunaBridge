package dev.lunabridge.paper;

import org.bukkit.entity.Player;

/** Answers whether a player may be included in public bridge output. */
@FunctionalInterface
public interface PublicVisibilityProvider {
    boolean isPublic(Player player);
}
