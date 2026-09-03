package dev.lunabridge.discord;

import java.util.List;

/** Platform port used by Discord commands; no Bukkit or Velocity type crosses the module boundary. */
@FunctionalInterface
public interface PlayerDirectory {
    List<String> onlinePlayerNames();

    /** Groups are snapshot data only; Discord threads never receive platform player objects. */
    default List<PlayerGroup> onlinePlayersByServer() {
        List<String> names = onlinePlayerNames();
        return names.isEmpty() ? List.of() : List.of(new PlayerGroup("Minecraft", names));
    }

    record PlayerGroup(String serverName, List<String> playerNames) {
        public PlayerGroup {
            serverName = serverName == null || serverName.isBlank() ? "Minecraft" : serverName;
            playerNames = List.copyOf(playerNames);
        }
    }
}
