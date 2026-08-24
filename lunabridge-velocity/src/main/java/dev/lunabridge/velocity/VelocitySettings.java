package dev.lunabridge.velocity;

import dev.lunabridge.core.config.ConfigMigration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Versioned Velocity properties with backup-before-rewrite and no silent future downgrade. */
final class VelocitySettings {
    final String sharedPass;
    final int pendingDeliveries;
    final int dedupEntries;
    final String discordToken;
    final Map<String, String> discordChannels;
    final Properties properties;

    private VelocitySettings(String sharedPass, int pendingDeliveries, int dedupEntries, String discordToken,
                             Map<String, String> discordChannels, Properties properties) {
        this.sharedPass = sharedPass; this.pendingDeliveries = pendingDeliveries; this.dedupEntries = dedupEntries;
        this.discordToken = discordToken; this.discordChannels = Map.copyOf(discordChannels); this.properties = properties;
    }

    static VelocitySettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.properties");
        if (!Files.exists(file)) try (InputStream defaults = VelocitySettings.class.getResourceAsStream("/config.properties")) {
            if (defaults == null) throw new IOException("bundled config missing");
            Files.copy(defaults, file);
        }
        Properties properties = new Properties();
        try (var input = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(input); }
        Map<String, String> schema = new LinkedHashMap<>();
        for (String key : new String[] {"config-version", "network.velocity", "network.shared-pass", "limits.network-outbox", "limits.dedup-entries"}) {
            schema.put(key, properties.getProperty(key, ""));
        }
        schema.put("network.velocity", "true");
        schema.put("limits.network-outbox", properties.getProperty("limits.pending-deliveries", "1024"));
        ConfigMigration.Result migration = ConfigMigration.migrate(schema);
        if (migration.newerSchema()) throw new IllegalStateException("Velocity configuration schema is newer than this LunaBridge build");
        if (migration.changed()) {
            Files.copy(file, file.resolveSibling("config.properties.v0.bak"), StandardCopyOption.REPLACE_EXISTING);
            properties.setProperty("config-version", migration.values().get("config-version"));
            properties.putIfAbsent("network.shared-pass", migration.values().get("network.shared-pass"));
            properties.putIfAbsent("limits.pending-deliveries", migration.values().get("limits.network-outbox"));
            properties.putIfAbsent("limits.dedup-entries", migration.values().get("limits.dedup-entries"));
            try (OutputStream output = Files.newOutputStream(file)) { properties.store(output, "LunaBridge Velocity configuration"); }
        }
        Map<String, String> channels = new LinkedHashMap<>();
        for (String property : properties.stringPropertyNames()) if (property.startsWith("discord.channels.")) {
            String key = property.substring("discord.channels.".length());
            String channelId = properties.getProperty(property, "").trim();
            if (key.matches("[a-z0-9][a-z0-9._-]{0,63}") && channelId.matches("[0-9]{5,32}")) channels.put(key, channelId);
        }
        return new VelocitySettings(properties.getProperty("network.shared-pass", ""),
                bounded(properties, "limits.pending-deliveries", 1024, 1, 4096),
                bounded(properties, "limits.dedup-entries", 10_000, 64, 100_000),
                properties.getProperty("discord.token", "").trim(), channels, properties);
    }

    private static int bounded(Properties properties, String key, int fallback, int min, int max) {
        int value;
        try { value = Integer.parseInt(properties.getProperty(key, Integer.toString(fallback))); }
        catch (NumberFormatException invalid) { throw new IllegalStateException(key + " must be an integer"); }
        if (value < min || value > max) throw new IllegalStateException(key + " must be " + min + ".." + max);
        return value;
    }
}
