# Operations and migration

## Minimal configuration

Velocity `plugins/lunabridge-velocity/config.properties`:

```properties
network.shared-pass=use-a-random-secret-at-least-16-characters
discord.token=PUT_DISCORD_BOT_TOKEN_HERE
discord.channels.global=123456789012345678
```

Every Paper `plugins/LunaBridge-Paper/config.yml`:

```yaml
server:
  id: "lobby" # must equal the Velocity backend name
network:
  velocity: true
  shared-pass: "use-a-random-secret-at-least-16-characters"
bridges:
  global:
    luna-channel: "Global"
```

Do not put a Discord token on Paper. Keep the passphrase out of source control. Rotate it on Velocity and every backend together; a partial rotation intentionally prevents network bridging until the configuration is consistent.

## Migration and lifecycle

Both configurations are schema versioned. Before a schema rewrite, LunaBridge creates `config.yml.v0.bak` or `config.properties.v0.bak`; existing values win, repeat migration is idempotent, and a newer schema is rejected rather than downgraded. Velocity persists a monotonically increasing network epoch and a capped first-login UUID ledger.

On disable, Paper unregisters its plugin-message channels and drops only bounded bridge state. Velocity unregisters the channel, closes the sole JDA instance, clears sessions, and destroys derived secret material. LunaChat's listeners, commands, and local chat behavior are never registered or altered by LunaBridge.

## Failure diagnosis

Check the Velocity log for the backend name, request UUID, and rejection code. Do not increase queue limits to conceal an outage. Fix the carrier, backend identity, or shared passphrase, then allow a fresh HMAC session to establish. Local LunaChat traffic continuing while the bridge is unavailable is expected behavior.
