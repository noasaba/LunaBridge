# Test strategy

`./gradlew test` compiles the Paper adapter against the official LunaChat 3.0.16 artifact and runs deterministic core tests for:

- HMAC handshake success and transcript binding;
- AES-GCM tamper detection and replay rejection;
- strict message serialization;
- bounded deduplication and retry admission;
- explicit bridge-key mappings; and
- config migration/future-schema refusal.

The project does not automatically boot server processes or contact Discord. A live process lane must be explicitly isolated and use a disposable network and Discord guild. Before enabling it, provide actual Paper, Velocity, and LunaChat JARs plus a non-production Discord token; never use production credentials. The intended acceptance cases are cross-backend channel delivery, both Discord relay directions, `!p` and `/players`, seven notification types and separate channels, transient proxy/Discord/ACK failures with local chat intact, duplicate-frame rejection, shared-pass rejection, and clean disable/reload.

LunaChat is compiled from its official released artifact, not mocked, but live server execution is deliberately gated because this repository contains no server binaries or credentials.
