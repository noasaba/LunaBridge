package dev.lunabridge.velocity;

import dev.lunabridge.discord.DiscordSettings;
import dev.lunabridge.discord.DiscordToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class VelocitySettings {
    private static final int CURRENT_SCHEMA = 5;
    final DiscordSettings discord;

    private VelocitySettings(DiscordSettings discord) { this.discord = discord; }

    static VelocitySettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.properties");
        if (!Files.exists(file)) try (InputStream defaults = VelocitySettings.class.getResourceAsStream("/config.properties")) {
            if (defaults == null) throw new IOException("bundled config missing");
            Files.copy(defaults, file);
        }
        Properties properties = new Properties();
        try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(input); }
        int version;
        try { version = Integer.parseInt(properties.getProperty("config-version", "0")); }
        catch (NumberFormatException invalid) { throw new IllegalStateException("config-version must be numeric"); }
        if (version > CURRENT_SCHEMA) throw new IllegalStateException("Velocity configuration schema is newer than this LunaBridge build");
        if (hasLegacySettings(properties)) {
            Files.copy(file, file.resolveSibling("config.properties.v0.bak"), StandardCopyOption.REPLACE_EXISTING);
            properties.keySet().removeIf(key -> key.toString().startsWith("network.") || key.toString().startsWith("limits.")
                    || key.toString().equals("server.id") || isLegacyChannelKey(key.toString()));
        }
        properties.putIfAbsent("discord.minecraft-chat-format", DiscordSettings.DEFAULT_MINECRAFT_CHAT_FORMAT);
        properties.putIfAbsent("discord.external-display-name-format", DiscordSettings.DEFAULT_EXTERNAL_DISPLAY_NAME_FORMAT);
        properties.setProperty("config-version", Integer.toString(CURRENT_SCHEMA));
        try (OutputStream output = Files.newOutputStream(file)) { properties.store(output, "LunaBridge Velocity configuration"); }

        Map<String, String> mappings = new LinkedHashMap<>();
        for (String property : properties.stringPropertyNames()) {
            String prefix = "discord.channels.";
            String suffix = ".lunachat-channel-id";
            if (!property.startsWith(prefix) || !property.endsWith(suffix)) continue;
            String discordChannelId = property.substring(prefix.length(), property.length() - suffix.length());
            String stableId = properties.getProperty(property, "").trim();
            if (stableId.isEmpty()) continue;
            validateMapping(discordChannelId, stableId);
            if (mappings.put(discordChannelId, stableId) != null) throw new IllegalStateException("duplicate Discord channel mapping");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (String property : properties.stringPropertyNames()) options.put(property, properties.getProperty(property, ""));
        String token = DiscordToken.resolve(properties.getProperty("discord.token", ""),
                properties.getProperty("discord.token-file", ""), dataDirectory);
        return new VelocitySettings(new DiscordSettings(token, mappings, options));
    }

    static void saveMapping(Path dataDirectory, String discordChannelId, String stableId) throws IOException {
        validateMapping(discordChannelId, stableId);
        Path file = dataDirectory.resolve("config.properties");
        Properties properties = new Properties();
        try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(input); }
        properties.setProperty("config-version", Integer.toString(CURRENT_SCHEMA));
        properties.setProperty("discord.channels." + discordChannelId + ".lunachat-channel-id", stableId);
        Path temporary = file.resolveSibling("config.properties.tmp");
        try (OutputStream output = Files.newOutputStream(temporary)) {
            properties.store(output, "LunaBridge Velocity configuration");
        }
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean hasLegacySettings(Properties properties) {
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith("network.") || key.startsWith("limits.") || key.equals("server.id") || isLegacyChannelKey(key)) return true;
        }
        return false;
    }

    private static boolean isLegacyChannelKey(String key) {
        return key.startsWith("discord.channels.") && !key.endsWith(".lunachat-channel-id");
    }

    private static void validateMapping(String discordChannelId, String stableId) {
        if (!discordChannelId.matches("[0-9]{5,32}")) throw new IllegalStateException("invalid Discord channel id");
        try {
            if (!UUID.fromString(stableId).toString().equals(stableId)) throw new IllegalStateException("ChannelId must be canonical UUID");
        } catch (IllegalArgumentException invalid) { throw new IllegalStateException("ChannelId must be canonical UUID", invalid); }
    }
}
