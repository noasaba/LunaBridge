# Architecture

```text
Paper backend                         Velocity (one instance)
-------------                         -----------------------
LunaChat ── public confirmed event ─▶ authenticated network authority ─▶ Discord Gateway (JDA)
     ▲                 │                       │                         │
     └─ public chatFromOtherSource ◀───────────┘                         └─ Discord ingress
```

## Ownership

| Area | Owner |
| --- | --- |
| Channel creation, membership, passwords, mute/ban, format, local recipients, `/ch` | LunaChat |
| Player UUID → backend presence and network routing | Velocity |
| Discord bot connection, `!p`, `/players`, notifications | Velocity |
| Authentication session and bounded outbox edge | Paper adapter |

Paper observes LunaChat 3.0.16's public legacy-compatible `LunaChatChannelMessageEvent` at monitor priority and snapshots it on the next main-thread task. LunaChat fires this after its v3 Bukkit event, so later legacy and MONITOR mutations have completed before LunaBridge publishes; a final empty recipient set suppresses publication. It does not cancel `AsyncChatEvent`, change recipients or formatting, or register `/ch`, `/tell`, `/msg`, or `/r`. On a remote arrival, the adapter invokes LunaChat's public `Channel.chatFromOtherSource`; it does not reconstruct LunaChat internals.

## Identity and loop prevention

Minecraft authors are resolved to Bukkit UUIDs on the main thread. Display names are presentation only. A message UUID is its sole logical delivery identity. Secure frames have a separate `session + epoch + sequence` identity. Each configured bridge key is a stable lower-case key explicitly mapped to one LunaChat channel name. Paper authenticates its complete mapping in the handshake, and Velocity rejects conflicts between backends before routing.

Remote injection is guarded at three layers:

1. The `BridgeOrigin` is serialized end-to-end.
2. A scoped `ThreadLocal` marks the synchronous LunaChat reinjection so its observation event is ignored.
3. The bounded delivery state machine suppresses the same logical UUID through its ten-second deadline plus replay grace; a retry receives an ACK without another injection.

Only `MINECRAFT` origin messages may relay to Discord. Discord ingress retains `DISCORD` origin across every Minecraft destination, so reinjection cannot make a Discord→Minecraft→Discord loop.

## Discord feature inventory

The Velocity gateway provides Minecraft→Discord channel relay; allowlisted Discord→Minecraft relay; text `!p` and slash `/players`; startup, shutdown, join, quit, first-login, login, and server-switch notifications; per-notification Discord channels; and an optional first-login role alert. The configured Discord channel map is both the ingress and command allowlist. Duplicate Discord channel IDs are rejected at configuration load. No Paper node opens JDA, so duplicate gateway ownership cannot occur.

The initial scope deliberately does not take ownership of LunaChat `/tell`, `/msg`, or `/r`. LunaChat v3's public integration surface does not expose a separate completed PM-routing hook that would let a second network authority safely replace cross-backend recipient resolution without command conflict. A future network-PM feature must use an explicitly new command/API boundary, UUID recipient policy, durable encrypted offline storage, and a migration—not a LunaChat command override.
