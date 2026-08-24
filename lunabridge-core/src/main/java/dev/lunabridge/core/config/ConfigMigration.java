package dev.lunabridge.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small, idempotent schema migrator. File I/O/backups stay in platform adapters. */
public final class ConfigMigration {
    public static final int CURRENT_VERSION = 1;
    public record Result(Map<String, String> values, boolean changed, boolean newerSchema) { }
    private ConfigMigration() { }

    public static Result migrate(Map<String, String> input) {
        Map<String, String> values = new LinkedHashMap<>(input);
        int version;
        try {
            String raw = values.getOrDefault("config-version", "0");
            version = raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw);
        }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("config-version must be numeric"); }
        if (version > CURRENT_VERSION) return new Result(Map.copyOf(values), false, true);
        boolean changed = false;
        changed |= putIfAbsent(values, "network.velocity", "true");
        changed |= putIfAbsent(values, "network.shared-pass", "");
        changed |= putIfAbsent(values, "limits.network-outbox", "256");
        changed |= putIfAbsent(values, "limits.dedup-entries", "10000");
        if (version != CURRENT_VERSION) { values.put("config-version", Integer.toString(CURRENT_VERSION)); changed = true; }
        return new Result(Map.copyOf(values), changed, false);
    }
    private static boolean putIfAbsent(Map<String, String> values, String key, String value) { if (values.containsKey(key)) return false; values.put(key, value); return true; }
}
