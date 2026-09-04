# LunaBridge

LunaBridge is a Discord integration consumer for LunaChat. The boundary is:

日本語の導入・運用・API利用方法は
[LunaChat 4 + LunaBridge 統合導入・API利用ガイド](docs/LUNACHAT_LUNABRIDGE_GUIDE_JA.md)
にまとめています。

> LunaChat owns Minecraft chat and Minecraft inter-server networking. LunaBridge owns Discord integration only.

Supported topologies:

- Standalone: `LunaChat-Paper` + `lunabridge-paper-standalone.jar`.
- Network: `LunaChat-Velocity` + `LunaChat-Paper` backends + `lunabridge-velocity.jar` on the proxy. Do not install the Paper standalone bridge on network backends.

Both bridge artifacts consume the Frozen LunaChat Integration API v1:

```text
com.github.ucchyocean:lunachat-api:1.0.0-SNAPSHOT
```

The API is a `compileOnly`/provided contract and is never shaded into LunaBridge. LunaBridge discovers it through Bukkit `ServicesManager` on standalone Paper and through LunaChat's `LunaChatApiProvider.current()` on Velocity. The expected roles are respectively `STANDALONE_AUTHORITY` and `NETWORK_AUTHORITY`; network edges are rejected before JDA starts.

Discord mappings use stable LunaChat channel IDs, not channel names:

```yaml
bridges:
  "123456789012345678":
    lunachat-channel-id: "550e8400-e29b-41d4-a716-446655440000"
```

Discord-origin messages are submitted only with `MessageGateway.publishExternal`, using `lunabridge:discord` and the Discord message ID as the provider idempotency key. Only accepted messages whose origin is `MINECRAFT` are relayed back to Discord.

The build targets Java 25, Paper API `26.2.build.117-stable`, Velocity API `4.1.0-SNAPSHOT`, and JDA `6.4.1`.

## Build

```text
./gradlew clean test assemble --rerun-tasks
```

For a local LunaChat checkout whose API artifact has not been installed to a Maven repository, pass the provided artifact explicitly:

```text
./gradlew clean test assemble --rerun-tasks \
  -PlunaChatApiJar=/path/to/lunachat-api-1.0.0-SNAPSHOT.jar
```

Minecraft legacy color and decoration codes are removed at the Discord presentation boundary; the Frozen API message and Minecraft rendering remain untouched.
Minecraft-to-Discord text can be customized with `discord.minecraft-chat-format`
using `{channel}`, `{username}`, `{message}`, and `{japanized}` placeholders.

## Quick setup

Put the bot token in a separate file, set `discord.token-file` to its absolute path (or a path relative to the plugin data directory), then use the server console:

```text
lunabridge setup <discord-channel-id> <lunachat-channel-name-or-alias>
lunabridge doctor
```

`setup` resolves the name once through the Frozen API, refuses channels that do not accept external messages, persists only the stable `ChannelId`, and applies the mapping without reconnecting JDA. Its Discord test is reported as queued only when the gateway is ready, the channel exists in JDA's cache, the bot can talk there, and the bounded outbound queue accepts it. Both administration commands are console-only.

The product version is `0.3.0-beta.13`, generated from `gradle.properties`.
