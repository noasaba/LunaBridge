# Operations

LunaBridge is configured only with Discord connector settings and stable LunaChat channel IDs. It does not contain a network passphrase, server ID, ACK, handshake, carrier, or transport setting.

Velocity configuration example:

```properties
config-version=5
discord.token-file=/secure/path/discord-token
discord.minecraft-chat-format=[{channel}] {username}: {message}{japanized}
discord.external-display-name-format=Discord:{username}
```

Then run `lunabridge setup 123456789012345678 global` from the Velocity console or from a player with `lunabridge.admin`. The command resolves the name or alias once, requires external publishing, saves the stable UUID mapping, and applies it live. It reports the Discord test as queued only after confirming that JDA is ready, the channel is known, the bot can talk there, and the outbound queue accepted the operation. Run `lunabridge doctor` to inspect API role/version, LunaChat network status, token presence, Discord readiness, and every mapping. Network `READY` is `OK`, `RELOADING` is `WAIT`, and all degraded or stopping states are `FAIL`. Both commands require `lunabridge.admin` for players; the console is always authorized. `doctor` reports only whether a token is configured, never its value.

When Velocity runs under systemd, its standard input is normally unavailable after startup. Grant the permission through LuckPerms (or an equivalent Velocity permission provider), then run the commands in game:

```text
/lp user <player> permission set lunabridge.admin true
/lunabridge doctor
/lunabridge setup <discord-channel-id> <lunachat-channel-name-or-alias>
```

If `doctor` reports an unknown or disabled mapping, remove it with
`/lunabridge unmap <discord-channel-id>` and configure it again after the
LunaChat channel is available.

On Velocity, LunaBridge starts its Discord gateway only after the Minecraft
listener has bound successfully. A failed bind therefore cannot leave a
Discord-only orphan. Listener close, proxy pre-shutdown, and proxy shutdown
close the LunaChat subscription, retry executor, and JDA gateway; JDA is
forced down and awaited for up to five seconds. A token-wide cross-host lease
is intentionally not used because one bot may legitimately serve multiple
healthy proxies. Do not map the same Discord channel to multiple live proxy
instances unless duplicate responses are intended.

When SVSync is installed, LunaBridge obtains `SVSyncApi` from the `svsync`
plugin instance and subscribes to its visibility transitions. `HIDDEN` players
are excluded from automatic presence output; `PUBLIC` and not-yet-received
`UNKNOWN` states remain public. A connected `HIDDEN -> PUBLIC` transition emits
the normal login notification once, while initial `UNKNOWN -> PUBLIC` state
does not duplicate the join notification. The subscription is closed with the
Velocity bridge lifecycle. The API remains compile-only and is never shaded.

Multiple Discord channel IDs may map to the same stable LunaChat `ChannelId`; Minecraft messages fan out to every configured Discord destination.

Minecraft-to-Discord chat text is controlled by `discord.minecraft-chat-format`
on Velocity, or `discord.minecraft-chat-format` in Paper YAML. Available
placeholders are `{channel}`, `{username}`, `{message}`, and `{japanized}`;
double-brace forms such as `{{message}}` are accepted too. `{japanized}` is
empty when LunaChat did not append a Japanese conversion, and otherwise
contains the leading space and parentheses, so the default format does not
leave extra punctuation.

Discord-to-Minecraft author names are controlled separately by
`discord.external-display-name-format`. Its default is `Discord:{username}`.
`{username}` is Discord's effective display name after Legacy formatting,
line breaks, and control characters have been removed. This affects only the
LunaChat external author display name, producing `Discord:NAME: message` with
the default LunaChat channel format.

For Discord-to-Minecraft messages, image attachments are appended as their
Discord CDN URLs. A post containing only images therefore appears as the URL
or URLs in Minecraft chat; non-image attachments are not forwarded. When the
combined message exceeds Minecraft's external-message limit, message text is
shortened before the image URLs so the links remain visible.

Inline `discord.token` remains backward compatible, but `discord.token-file` takes precedence and avoids copying the credential into generated configuration. Relative token-file paths are resolved from the plugin data directory. Paper standalone uses the equivalent YAML keys under `discord`.

A channel mapping is checked with `api.channels().find(channelId)` at startup; missing IDs, unsupported roles, unavailable providers, incompatible API majors, and missing capabilities fail closed before JDA connects while the administration command remains available for diagnosis where the platform permits it.

When an old config contains network settings or name-based mappings, LunaBridge
writes a non-overwriting generation backup such as `config.properties.v0.bak`
or `.bak.1`. Removed keys remain in place as commented evidence with their
removal schema instead of disappearing. Migration proceeds one schema at a
time, validates a temporary file, and atomically replaces the original only
after validation. It never logs a network secret or silently turns a channel
name into a permanent ID.

On disable, the API subscription is closed before the Discord connector. JDA outbound work is drained for at most three seconds during the Velocity shutdown notification; pending publish retries are cancelled. Local LunaChat operation is not owned or stopped by LunaBridge.
