# Operations

LunaBridge is configured only with Discord connector settings and stable LunaChat channel IDs. It does not contain a network passphrase, server ID, ACK, handshake, carrier, or transport setting.

Velocity configuration example:

```properties
config-version=4
discord.token-file=/secure/path/discord-token
discord.minecraft-chat-format=[{channel}] {username}: {message}{japanized}
```

Then run `lunabridge setup 123456789012345678 global` from the server console. The command resolves the name or alias once, requires external publishing, saves the stable UUID mapping, and applies it live. It reports the Discord test as queued only after confirming that JDA is ready, the channel is known, the bot can talk there, and the outbound queue accepted the operation. Run `lunabridge doctor` to inspect API role/version, LunaChat network status, token presence, Discord readiness, and every mapping. Network `READY` is `OK`, `RELOADING` is `WAIT`, and all degraded or stopping states are `FAIL`. Administration is console-only.

Multiple Discord channel IDs may map to the same stable LunaChat `ChannelId`; Minecraft messages fan out to every configured Discord destination.

Minecraft-to-Discord chat text is controlled by `discord.minecraft-chat-format`
on Velocity, or `discord.minecraft-chat-format` in Paper YAML. Available
placeholders are `{channel}`, `{username}`, `{message}`, and `{japanized}`;
double-brace forms such as `{{message}}` are accepted too. `{japanized}` is
empty when LunaChat did not append a Japanese conversion, and otherwise
contains the leading space and parentheses, so the default format does not
leave extra punctuation.

Inline `discord.token` remains backward compatible, but `discord.token-file` takes precedence and avoids copying the credential into generated configuration. Relative token-file paths are resolved from the plugin data directory. Paper standalone uses the equivalent YAML keys under `discord`.

A channel mapping is checked with `api.channels().find(channelId)` at startup; missing IDs, unsupported roles, unavailable providers, incompatible API majors, and missing capabilities fail closed before JDA connects while the administration command remains available for diagnosis where the platform permits it.

When an old config contains network settings or name-based mappings, LunaBridge writes `config.yml.v0.bak` or `config.properties.v0.bak`, removes the old transport keys, and logs that stable ChannelIds must be configured manually. It never copies a network secret into the new configuration and never silently turns a channel name into a permanent ID.

On disable, the API subscription is closed before the Discord connector. JDA outbound work is drained for at most three seconds during the Velocity shutdown notification; pending publish retries are cancelled. Local LunaChat operation is not owned or stopped by LunaBridge.
