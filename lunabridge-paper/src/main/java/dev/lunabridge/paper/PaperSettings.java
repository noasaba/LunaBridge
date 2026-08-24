package dev.lunabridge.paper;

import dev.lunabridge.core.config.ConfigMigration;
import dev.lunabridge.core.model.BridgeChannelMapping;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

final class PaperSettings {
    final String serverId;
    final boolean velocityEnabled;
    final String sharedPass;
    final BridgeChannelMapping channels;
    final int outboxLimit;
    final int dedupLimit;

    private PaperSettings(String serverId, boolean velocityEnabled, String sharedPass, BridgeChannelMapping channels,
                          int outboxLimit, int dedupLimit) {
        this.serverId = serverId;
        this.velocityEnabled = velocityEnabled;
        this.sharedPass = sharedPass;
        this.channels = channels;
        this.outboxLimit = outboxLimit;
        this.dedupLimit = dedupLimit;
    }

    static PaperSettings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration config = plugin.getConfig();
        Map<String, String> schemaValues = Map.of(
                "config-version", Integer.toString(config.getInt("config-version", 0)),
                "network.velocity", Boolean.toString(config.getBoolean("network.velocity", true)),
                "network.shared-pass", config.getString("network.shared-pass", ""),
                "limits.network-outbox", Integer.toString(config.getInt("limits.network-outbox", 256)),
                "limits.dedup-entries", Integer.toString(config.getInt("limits.dedup-entries", 10_000)));
        ConfigMigration.Result migration = ConfigMigration.migrate(schemaValues);
        if (migration.newerSchema()) throw new IllegalStateException("configuration schema is newer than this LunaBridge build");
        if (migration.changed()) {
            backup(plugin);
            migration.values().forEach(config::set);
            plugin.saveConfig();
        }
        String serverId = config.getString("server.id", "").trim();
        if (!serverId.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalStateException("server.id must be 1-64 safe characters");
        Map<String, String> mapping = new LinkedHashMap<>();
        ConfigurationSection bridges = config.getConfigurationSection("bridges");
        if (bridges != null) for (String key : bridges.getKeys(false)) {
            String luna = bridges.getString(key + ".luna-channel", "").trim();
            if (!luna.isEmpty()) mapping.put(key, luna);
        }
        if (mapping.isEmpty()) plugin.getLogger().warning("No bridges are configured; LunaBridge will observe nothing.");
        return new PaperSettings(serverId, config.getBoolean("network.velocity", true),
                config.getString("network.shared-pass", ""), new BridgeChannelMapping(mapping),
                bounded(config.getInt("limits.network-outbox", 256), 1, 4096, "limits.network-outbox"),
                bounded(config.getInt("limits.dedup-entries", 10_000), 64, 100_000, "limits.dedup-entries"));
    }

    private static int bounded(int value, int min, int max, String path) {
        if (value < min || value > max) throw new IllegalStateException(path + " must be " + min + ".." + max);
        return value;
    }
    private static void backup(JavaPlugin plugin) {
        try {
            var source = plugin.getDataFolder().toPath().resolve("config.yml");
            if (Files.exists(source)) Files.copy(source, source.resolveSibling("config.yml.v0.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new IllegalStateException("could not back up config before migration", error); }
    }
}
