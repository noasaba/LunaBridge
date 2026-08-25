# Test strategy

`./gradlew test` compiles the Paper adapter against the official LunaChat 3.0.16 artifact and runs deterministic core tests for:

- HMAC handshake success and transcript binding;
- AES-GCM tamper detection and replay rejection;
- strict message serialization;
- cold-start handshake followed by Discord-origin delivery;
- heartbeat detection and logical-work preservation across Velocity restart;
- lost-ACK fresh-frame retry, receiver deduplication, and ACK completion;
- exact-frame replay rejection without session destruction;
- bounded idempotency recovery at deadline + replay grace;
- session expiry and admission-counter reconciliation;
- final LunaChat 3.0.16 legacy-compatible message and recipient mutation;
- dynamic carrier reselection and Discord loop policy;
- explicit bridge-key mappings; and
- config migration/future-schema refusal, duplicate Discord mapping rejection, command scope, and durable first-login corruption handling.

The Gradle test task does not automatically boot server processes or contact Discord. A live process lane must be explicitly isolated and use a disposable network and Discord guild. Before enabling it, provide actual Paper, Velocity, and LunaChat JARs plus a non-production Discord token; never use production credentials. The intended acceptance cases are cross-backend channel delivery, both Discord relay directions, `!p` and `/players`, seven notification types and separate channels, transient proxy/Discord/ACK failures with local chat intact, duplicate-frame rejection, shared-pass rejection, and clean disable/reload.

LunaChat is compiled from its official released artifact, not mocked, but live server execution is deliberately gated because this repository contains no server binaries or credentials.
