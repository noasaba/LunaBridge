package dev.lunabridge.core.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Explicit, operator-owned mapping prevents a mutable LunaChat display name becoming network identity. */
public final class BridgeChannelMapping {
    private final Map<String, String> byLunaName;
    private final Map<String, String> byBridgeKey;

    public BridgeChannelMapping(Map<String, String> bridgeKeyToLunaName) {
        Objects.requireNonNull(bridgeKeyToLunaName, "bridgeKeyToLunaName");
        Map<String, String> forward = new LinkedHashMap<>();
        Map<String, String> reverse = new LinkedHashMap<>();
        bridgeKeyToLunaName.forEach((key, lunaName) -> {
            if (key == null || lunaName == null || key.isBlank() || lunaName.isBlank()) {
                throw new IllegalArgumentException("blank channel mapping");
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
