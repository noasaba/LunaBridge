package dev.lunabridge.core.model;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Explicit, operator-owned mapping prevents a mutable LunaChat display name becoming network identity. */
public final class BridgeChannelMapping {
    private static final Pattern KEY = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private final Map<String, String> byLunaName;
    private final Map<String, String> byBridgeKey;

    public BridgeChannelMapping(Map<String, String> bridgeKeyToLunaName) {
        Objects.requireNonNull(bridgeKeyToLunaName, "bridgeKeyToLunaName");
        Map<String, String> forward = new LinkedHashMap<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        bridgeKeyToLunaName.forEach((key, lunaName) -> {
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalArgumentException("bridge channel must be a stable lower-case key");
            }
            if (lunaName == null || lunaName.isBlank() || lunaName.getBytes(StandardCharsets.UTF_8).length > 128) {
                throw new IllegalArgumentException("LunaChat channel is missing or too long");
            }
            if (forward.put(lunaName, key) != null || reverse.put(key, lunaName) != null) {
                throw new IllegalArgumentException("channel mappings must be one-to-one");
            }
        });
        this.byLunaName = Map.copyOf(forward);
        this.byBridgeKey = Map.copyOf(reverse);
    }

    public Optional<String> bridgeKeyForLunaChannel(String lunaName) {
        return Optional.ofNullable(byLunaName.get(lunaName));
    }

    public Optional<String> lunaChannelForBridgeKey(String bridgeKey) {
        return Optional.ofNullable(byBridgeKey.get(bridgeKey));
    }

    public Map<String, String> asBridgeKeyToLunaName() {
        return byBridgeKey;
    }
}
