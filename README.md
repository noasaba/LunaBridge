# LunaBridge

LunaBridge is a secure network and Discord bridge for [LunaChat](https://github.com/ucchyocean/LunaChat). It does not replace LunaChat: LunaChat owns Minecraft chat channels, formatting, permissions, moderation, `/ch`, `/tell`, and `/r`; LunaBridge observes confirmed channel messages and extends them across a Velocity network.

## Install

Install `lunabridge-paper/build/libs/lunabridge-paper-0.1.0-SNAPSHOT.jar` plus LunaChat v3.0.16 on every Paper backend, and `lunabridge-velocity/build/libs/lunabridge-velocity-0.1.0-SNAPSHOT.jar` on Velocity. Configure the same random 16+ character `network.shared-pass` in each Paper `config.yml` and the Velocity `config.properties`.

Only Velocity owns the Discord bot. Paper has no JDA dependency, no Discord token, and no Discord connection.

The one required bridge mapping is stable bridge key → LunaChat channel name, for example `global` → `Global`. The key, rather than a LunaChat display name, becomes the network identity.

## Build

```sh
./gradlew test
./gradlew assemble
```

Build toolchain: Java 25; Paper/core bytecode: Java 21; Velocity bytecode: Java 25. The build uses Paper API `1.21.1-R0.1-SNAPSHOT`, Velocity API `4.1.0-SNAPSHOT`, JDA `6.4.1`, Gradle `9.6.1`, and the official LunaChat `3.0.16` artifact as an unbundled `compileOnly` dependency.

The old LunaChat POM has unavailable historical bStats transitive dependencies. LunaBridge explicitly resolves its official artifact non-transitively because it calls only the public LunaChat API; the generated LunaBridge JAR never embeds LunaChat.

## Design documents

- [Architecture and authority boundaries](docs/ARCHITECTURE.md)
- [Security and delivery semantics](docs/SECURITY_AND_DELIVERY.md)
- [Operations, migration, and compatibility scope](docs/OPERATIONS.md)
- [Test strategy and optional process E2E lane](docs/TESTING.md)
