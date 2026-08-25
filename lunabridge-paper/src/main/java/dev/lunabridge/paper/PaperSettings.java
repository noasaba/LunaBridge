package dev.lunabridge.paper;

import dev.lunabridge.discord.DiscordSettings;
import dev.lunabridge.discord.DiscordToken;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class PaperSettings {
    private static final int CURRENT_SCHEMA = 3;
    final DiscordSettings discord;

    private PaperSettings(DiscordSettings discord) { this.discord = discord; }

    static PaperSettings load(JavaPlugin plugin) {
        plugin.saveDefaultConfig();
        FileConfiguration config = plugin.getConfig();
        int version = config.getInt("config-version", 0);
        if (version > CURRENT_SCHEMA) throw new IllegalStateException("configuration schema is newer than this LunaBridge build");
        migrateLegacy(plugin, config);
        config.set("config-version", CURRENT_SCHEMA);
        plugin.saveConfig();

        Map<String, String> mappings = new LinkedHashMap<>();
        ConfigurationSection bridges = config.getConfigurationSection("bridges");
        if (bridges != null) for (String discordChannelId : bridges.getKeys(false)) {
            String stableId = bridges.getString(discordChannelId + ".lunachat-channel-id", "").trim();
            if (stableId.isEmpty()) continue;
            validateMapping(discordChannelId, stableId);
            if (mappings.put(discordChannelId, stableId) != null) throw new IllegalStateException("duplicate Discord channel mapping");
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("discord.notifications.channel-id", config.getString("discord.notifications.channel-id", ""));
        options.put("discord.notifications.enable.startup", Boolean.toString(config.getBoolean("discord.notifications.enable.startup", true)));
        options.put("discord.notifications.enable.shutdown", Boolean.toString(config.getBoolean("discord.notifications.enable.shutdown", true)));
        options.put("discord.notifications.enable.join", Boolean.toString(config.getBoolean("discord.notifications.enable.join", true)));
        options.put("discord.notifications.enable.quit", Boolean.toString(config.getBoolean("discord.notifications.enable.quit", true)));
        options.put("discord.notifications.startup", config.getString("discord.notifications.startup", "✅ Server started"));
        options.put("discord.notifications.shutdown", config.getString("discord.notifications.shutdown", "⛔ Server stopped"));
        options.put("discord.notifications.join", config.getString("discord.notifications.join", "➡️ {{player}} joined"));
        options.put("discord.notifications.quit", config.getString("discord.notifications.quit", "⬅️ {{player}} left"));
        String token;
        try {
            token = DiscordToken.resolve(config.getString("discord.token", ""),
                    config.getString("discord.token-file", ""), plugin.getDataFolder().toPath());
        } catch (IOException failure) {
            throw new IllegalStateException("could not read Discord token-file", failure);
        }
        return new PaperSettings(new DiscordSettings(token, mappings, options));
    }

    static void saveMapping(JavaPlugin plugin, String discordChannelId, String stableId) {
        validateMapping(discordChannelId, stableId);
        plugin.getConfig().set("config-version", CURRENT_SCHEMA);
        plugin.getConfig().set("bridges." + discordChannelId + ".lunachat-channel-id", stableId);
        plugin.saveConfig();
    }

    private static void validateMapping(String discordChannelId, String stableId) {
        if (!discordChannelId.matches("[0-9]{5,32}")) throw new IllegalStateException("invalid Discord channel id");
        try {
            if (!UUID.fromString(stableId).toString().equals(stableId)) throw new IllegalStateException("ChannelId must be canonical UUID");
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("ChannelId must be canonical UUID", invalid); }
    }

    private static void migrateLegacy(JavaPlugin plugin, FileConfiguration config) {
        boolean legacy = config.isConfigurationSection("network") || config.isConfigurationSection("server");
        ConfigurationSection bridges = config.getConfigurationSection("bridges");
        if (bridges != null) for (String key : bridges.getKeys(false)) legacy |= bridges.contains(key + ".luna-channel");
        if (!legacy) return;
        try {
            var source = plugin.getDataFolder().toPath().resolve("config.yml");
            if (Files.exists(source)) Files.copy(source, source.resolveSibling("config.yml.v0.bak"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) { throw new IllegalStateException("could not back up legacy configuration", error); }
        config.set("network", null);
        config.set("server", null);
        if (bridges != null) for (String key : bridges.getKeys(false)) config.set("bridges." + key + ".luna-channel", null);
        plugin.getLogger().warning("Legacy Minecraft network settings were removed after backup; configure Discord channel IDs to stable LunaChat ChannelIds.");
    }
}
