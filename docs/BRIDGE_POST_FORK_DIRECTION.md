# Post-fork LunaBridge direction

LunaBridge is now the Discord-only consumer of the Frozen LunaChat Integration API v1.

| Concern | Owner |
| --- | --- |
| Minecraft chat semantics, filtering, permissions, accepted-message boundary | LunaChat |
| Minecraft Paper/Velocity network transport, authentication, ACK/retry, replay, routing | LunaChat |
| Discord JDA lifecycle, Discord safety, REST queue, receipts, external publish retry | LunaBridge |

## Artifacts and topology

- `lunabridge-discord`: platform-independent JDA connector and Discord safety layer.
- `lunabridge-paper`: builds `lunabridge-paper-standalone.jar`; uses Bukkit service discovery and accepts only `STANDALONE_AUTHORITY`.
- `lunabridge-velocity`: builds `lunabridge-velocity.jar`; uses the required `lunachat` provider and accepts only `NETWORK_AUTHORITY`.

Standalone installs LunaChat-Paper and the Paper standalone bridge together. A network installs LunaChat-Paper on each backend, LunaChat-Velocity on the proxy, and LunaBridge-Velocity only on the proxy. A `NETWORK_EDGE` is never a Discord authority.

## Frozen API rules

The bridge depends on `com.github.ucchyocean:lunachat-api:1.0.0-SNAPSHOT` as `compileOnly`. It does not copy, extend, shade, reflect into, or provide a compatibility implementation for the API. API major mismatch, missing provider, missing authority role, or missing capability fails closed before JDA starts.

Persistent mappings are Discord channel snowflakes to stable LunaChat `ChannelId` values. Channel names are diagnostic display data only. Legacy network settings and name mappings are backed up and removed; no network secret is copied into the bridge configuration.

The old bridge transport classes and tests were deleted from this repository. There is no bridge-owned plugin-message channel, handshake, secure frame, ACK codec, replay window, carrier selection, network epoch, remote injection scope, or legacy LunaChat event observer.
