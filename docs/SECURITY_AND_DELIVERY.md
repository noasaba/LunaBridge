# Security and delivery boundary

LunaChat owns Minecraft inter-server authentication, transport security, replay protection, ACKs, and network delivery. LunaBridge does not reproduce or inspect those mechanisms.

The bridge security boundary is Discord connector safety: the Discord token remains platform configuration, configured channels are an ingress allowlist, user-controlled mentions are suppressed, Minecraft legacy color and decoration codes are removed only from Discord-bound presentation text, outbound queues and publish retries are bounded, request lifetime is enforced, and shutdown cancels pending work. Discord-origin messages retain the external origin through the Frozen API and are never relayed back as Minecraft-origin messages.

Observer receipts are bounded in memory with a TTL. They suppress duplicate `AcceptedMessage.messageId` notifications within the process, but the bridge does not claim durable exactly-once delivery across a process crash.
