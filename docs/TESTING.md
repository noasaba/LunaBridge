# Testing

The deterministic test suite covers:

- Frozen API v1 compilation and record construction;
- API major, exact runtime role, and required capability validation;
- `MINECRAFT`-only observer relay policy;
- bounded receipt eviction and TTL behavior without new-message starvation;
- fan-out preservation when multiple Discord channels map to one LunaChat channel;
- `doctor` classification for every LunaChat network state;
- Discord mention suppression, Minecraft legacy formatting removal, Unicode preservation, and surrogate-safe 2,000-character handling;
- token-file precedence, schema 2 to 3 additive migration, stable-ID setup persistence, and external-publish setup refusal;
- terminal external publish result handling and bounded retry infrastructure;
- Paper plugin metadata and Velocity generated metadata.

Run:

```text
./gradlew clean test assemble --rerun-tasks
```

The build uses the provided `lunachat-api-1.0.0-SNAPSHOT.jar` when passed with `-PlunaChatApiJar=...`; normal builds resolve the formal Maven coordinate. Live Paper, Velocity, LunaChat, and Discord process tests require disposable servers and credentials and are not run by this repository test task.
