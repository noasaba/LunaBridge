package dev.lunabridge.discord;

import java.util.List;

/** Platform port used by Discord commands; no Bukkit or Velocity type crosses the module boundary. */
@FunctionalInterface
public interface PlayerDirectory {
    List<String> onlinePlayerNames();
}
