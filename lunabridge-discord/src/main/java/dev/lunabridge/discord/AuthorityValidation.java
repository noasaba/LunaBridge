package dev.lunabridge.discord;

import com.github.ucchyocean.lunachat.api.Capability;
import com.github.ucchyocean.lunachat.api.LunaChatIntegrationApi;
import com.github.ucchyocean.lunachat.api.RuntimeRole;

import java.util.EnumSet;

/** Frozen API v1 role and capability gate shared by both platform adapters. */
public final class AuthorityValidation {
    private AuthorityValidation() { }

    public static void require(LunaChatIntegrationApi api, RuntimeRole expectedRole) {
        if (api == null) throw new IllegalStateException("LunaChat Integration API v1 is unavailable");
        if (api.apiVersion() == null || api.apiVersion().major() != 1) {
            throw new IllegalStateException("unsupported LunaChat Integration API major");
        }
        if (api.runtimeRole() != expectedRole) {
            throw new IllegalStateException("LunaChat runtime role is " + api.runtimeRole()
                    + "; expected " + expectedRole);
        }
        EnumSet<Capability> required = EnumSet.of(Capability.QUERY_CHANNELS,
                Capability.OBSERVE_ACCEPTED_MESSAGES, Capability.PUBLISH_EXTERNAL_MESSAGES);
        if (!api.capabilities().containsAll(required)) {
            throw new IllegalStateException("LunaChat Integration API capabilities are insufficient: " + api.capabilities());
        }
    }
}
