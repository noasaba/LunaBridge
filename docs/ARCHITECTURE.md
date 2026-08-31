# LunaBridge architecture

LunaBridge is a pure Discord connector. LunaChat owns channel semantics, filtering, final message acceptance, Minecraft chat, and all Paper-to-Paper or Paper-to-Velocity transport.

## Runtime boundaries

| Runtime | LunaChat role | LunaBridge role |
| --- | --- | --- |
| Standalone Paper | `STANDALONE_AUTHORITY` | Bukkit discovery, Discord lifecycle, player adapter |
| Velocity proxy | `NETWORK_AUTHORITY` | provider discovery, Discord lifecycle, proxy presence adapter |
| Network Paper backend | `NETWORK_EDGE` | LunaBridge is not installed |

The bridge validates API major `1`, the exact expected role, and `QUERY_CHANNELS`, `OBSERVE_ACCEPTED_MESSAGES`, and `PUBLISH_EXTERNAL_MESSAGES` before opening JDA.

## Message flow

Minecraft to Discord uses only `api.messages().observeAcceptedMessages(...)`. The bridge checks `origin.kind() == MINECRAFT`, resolves every Discord destination for the stable `ChannelId`, suppresses duplicate `messageId` values in a bounded TTL receipt cache, sanitizes mentions, and submits bounded Discord REST operations. At receipt capacity, the oldest ID is evicted so new accepted messages continue to flow.

Discord to Minecraft uses only `api.messages().publishExternal(...)`. The request contains `ExternalMessageIdentity("lunabridge:discord", discordMessageId)` and `MessageAuthor.External("lunabridge:discord", discordUserId, displayName)`. Retry is bounded by queue capacity, attempts, exponential backoff, and the request lifetime. `DUPLICATE` is success; terminal results are not retried; `retryable()` is honored for retryable failures.

No bridge class handles Paper plugin messages, handshake, secure frames, ACKs, replay windows, carrier selection, backend routing, remote injection, or legacy LunaChat events.

## Delivery limits

The common Discord layer owns JDA lifecycle, ready-time queue capacity, outbound capacity, mention suppression, surrogate-safe 2,000-character fitting, bounded publish retry, bounded observer receipts, slash-command registration/removal, and shutdown drain. A process crash between a successful Discord send and receipt state is not claimed to be exactly-once; the receipt is intentionally bounded and in-memory.
