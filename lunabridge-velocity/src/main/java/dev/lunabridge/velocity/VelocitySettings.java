package dev.lunabridge.velocity;

import dev.lunabridge.discord.DiscordSettings;
import dev.lunabridge.discord.DiscordToken;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
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
        Set<String> removals = new HashSet<>();
        if (hasLegacySettings(properties)) {
            Files.copy(file, file.resolveSibling("config.properties.v0.bak"), StandardCopyOption.REPLACE_EXISTING);
            properties.stringPropertyNames().stream().filter(key -> key.startsWith("network.") || key.startsWith("limits.")
                    || key.equals("server.id") || isLegacyChannelKey(key)).forEach(removals::add);
            removals.forEach(properties::remove);
        }
        Map<String, String> updates = new LinkedHashMap<>();
        addDefault(properties, updates, "discord.minecraft-chat-format", DiscordSettings.DEFAULT_MINECRAFT_CHAT_FORMAT);
        addDefault(properties, updates, "discord.external-display-name-format", DiscordSettings.DEFAULT_EXTERNAL_DISPLAY_NAME_FORMAT);
        properties.setProperty("config-version", Integer.toString(CURRENT_SCHEMA));
        if (version != CURRENT_SCHEMA) updates.put("config-version", Integer.toString(CURRENT_SCHEMA));
        if (!updates.isEmpty() || !removals.isEmpty()) updateFile(file, updates, removals);

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
        updateFile(file, Map.of("config-version", Integer.toString(CURRENT_SCHEMA),
                "discord.channels." + discordChannelId + ".lunachat-channel-id", stableId), Set.of());
    }

    static void removeMapping(Path dataDirectory, String discordChannelId) throws IOException {
        if (!discordChannelId.matches("[0-9]{5,32}")) throw new IllegalStateException("invalid Discord channel id");
        Path file = dataDirectory.resolve("config.properties");
        updateFile(file, Map.of("config-version", Integer.toString(CURRENT_SCHEMA)),
                Set.of("discord.channels." + discordChannelId + ".lunachat-channel-id"));
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

    private static void addDefault(Properties properties, Map<String, String> updates, String key, String value) {
        if (!properties.containsKey(key)) {
            properties.setProperty(key, value);
            updates.put(key, value);
        }
    }

    private static void updateFile(Path file, Map<String, String> requestedUpdates, Set<String> removals) throws IOException {
        Set<String> written = new HashSet<>();
        List<String> output = new java.util.ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String key = propertyKey(line);
            if (key == null) { output.add(line); continue; }
            if (removals.contains(key)) continue;
            if (requestedUpdates.containsKey(key)) {
                if (written.add(key)) output.add(key + "=" + requestedUpdates.get(key));
            } else output.add(line);
        }
        requestedUpdates.forEach((key, value) -> { if (!written.contains(key)) output.add(key + "=" + value); });
        Path temporary = file.resolveSibling("config.properties.tmp");
        Files.write(temporary, output, StandardCharsets.UTF_8);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String propertyKey(String line) {
        String trimmed = line.stripLeading();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) return null;
        int separator = trimmed.length();
        for (int index = 0; index < trimmed.length(); index++) {
            char value = trimmed.charAt(index);
            if (value == '=' || value == ':' || Character.isWhitespace(value)) { separator = index; break; }
        }
        return trimmed.substring(0, separator);
    }
}
