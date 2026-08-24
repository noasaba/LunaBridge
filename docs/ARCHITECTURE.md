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
| Player UUID → backend presence, routing, deduplication, delivery tracking | Velocity |
| Discord bot connection, `!p`, `/players`, notifications | Velocity |
| Authentication session and bounded outbox edge | Paper adapter |

Paper registers only `LunaChatBukkitChannelMessageEvent` at monitor priority. It does not cancel `AsyncChatEvent`, does not change recipients or formatting, and registers no `/ch`, `/tell`, `/msg`, or `/r` command. The event is from the official LunaChat v3.0.16 public Bukkit event surface and carries the masked/final LunaChat message. On a remote arrival, the adapter invokes LunaChat's public `Channel.chatFromOtherSource`; it does not reconstruct LunaChat internals.

## Identity and loop prevention

Minecraft authors are resolved to Bukkit UUIDs on the main thread. Display names are presentation only. Messages and traces are UUIDs. Each configured bridge key is a stable lower-case key explicitly mapped to one LunaChat channel name; inbound traffic is routed by that key and never trusts its remote display-channel field.

Remote injection is guarded at three layers:

1. The `BridgeOrigin` is serialized end-to-end.
2. A scoped `ThreadLocal` marks the synchronous LunaChat reinjection so its observation event is ignored.
3. A 24-hour, bounded message-ID deduplication cache drops duplicate network deliveries before injection.

## Discord feature inventory

The Velocity gateway provides Minecraft→Discord channel relay; allowlisted Discord→Minecraft relay; text `!p` and slash `/players`; startup, shutdown, join, quit, first-login, login, and server-switch notifications; per-notification Discord channels; and an optional first-login role alert. The configured Discord channel map is also the ingress allowlist. No Paper node opens JDA, so duplicate gateway ownership cannot occur.

The initial scope deliberately does not take ownership of LunaChat `/tell`, `/msg`, or `/r`. LunaChat v3's public integration surface does not expose a separate completed PM-routing hook that would let a second network authority safely replace cross-backend recipient resolution without command conflict. A future network-PM feature must use an explicitly new command/API boundary, UUID recipient policy, durable encrypted offline storage, and a migration—not a LunaChat command override.
