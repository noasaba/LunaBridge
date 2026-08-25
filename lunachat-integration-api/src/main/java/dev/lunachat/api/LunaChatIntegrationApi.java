package dev.lunachat.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Platform-neutral integration contract implemented by the maintained LunaChat fork. */
public interface LunaChatIntegrationApi {
    int API_MAJOR = 1;

    RuntimeRole runtimeRole();
    Set<Capability> capabilities();
    Optional<ChannelDescriptor> findChannel(ChannelId id);
    Optional<ChannelDescriptor> findChannelByNameOrAlias(String value);
    Subscription observeAcceptedMessages(AcceptedMessageListener listener);
    CompletionStage<ExternalPublishResult> publishExternal(ExternalMessageRequest request);

    enum RuntimeRole { STANDALONE_AUTHORITY, NETWORK_AUTHORITY, NETWORK_EDGE }
    enum Capability { QUERY_CHANNELS, OBSERVE_ACCEPTED_MESSAGES, PUBLISH_EXTERNAL_MESSAGES }
    enum OriginKind { MINECRAFT, EXTERNAL, SYSTEM }
    enum PublishStatus { ACCEPTED, DUPLICATE, CHANNEL_NOT_FOUND, FORBIDDEN, INVALID, OVER_CAPACITY, UNAVAILABLE, EXPIRED }

    record ChannelId(String value) {
        public ChannelId {
            value = require(value, "channel id", 128);
        }
    }

    record ChannelDescriptor(ChannelId id, String name, boolean acceptsExternalMessages) {
        public ChannelDescriptor {
            if (id == null) throw new IllegalArgumentException("channel id is required");
            name = require(name, "channel name", 128);
        }
    }

    record MessageOrigin(OriginKind kind, String namespace, String sourceMessageId) {
        public MessageOrigin {
            if (kind == null) throw new IllegalArgumentException("origin kind is required");
            namespace = require(namespace, "origin namespace", 128);
            sourceMessageId = require(sourceMessageId, "source message id", 256);
        }
    }

    sealed interface MessageAuthor permits PlayerAuthor, ExternalAuthor, SystemAuthor { }

    record PlayerAuthor(UUID uuid, String accountName, String displayName) implements MessageAuthor {
        public PlayerAuthor {
            if (uuid == null) throw new IllegalArgumentException("player UUID is required");
            accountName = require(accountName, "account name", 64);
            displayName = require(displayName, "display name", 256);
        }
    }

    record ExternalAuthor(String namespace, String stableId, String displayName) implements MessageAuthor {
        public ExternalAuthor {
            namespace = require(namespace, "author namespace", 128);
            stableId = require(stableId, "author stable id", 256);
            displayName = require(displayName, "display name", 256);
        }
    }

    record SystemAuthor(String name) implements MessageAuthor {
        public SystemAuthor { name = require(name, "system author", 128); }
    }

    record AcceptedMessage(UUID messageId, ChannelId channelId, String channelName, MessageOrigin origin,
                           MessageAuthor author, String sourceServerId, String content,
                           Instant createdAt, Instant expiresAt) {
        public AcceptedMessage {
            if (messageId == null || channelId == null || origin == null || author == null
                    || createdAt == null || expiresAt == null) throw new IllegalArgumentException("message fields are required");
            channelName = require(channelName, "channel name", 128);
            sourceServerId = sourceServerId == null ? "" : require(sourceServerId, "source server", 128);
            content = require(content, "content", 16_384);
            if (!expiresAt.isAfter(createdAt)) throw new IllegalArgumentException("message expiry must follow creation");
        }
    }

    record ExternalMessageIdentity(String namespace, String value) {
        public ExternalMessageIdentity {
            namespace = require(namespace, "identity namespace", 128);
            value = require(value, "identity value", 256);
        }
    }

    record ExternalMessageRequest(ChannelId channelId, ExternalMessageIdentity identity, ExternalAuthor author,
                                  String content, Instant createdAt, Duration requestedLifetime) {
        public ExternalMessageRequest {
            if (channelId == null || identity == null || author == null || createdAt == null || requestedLifetime == null)
                throw new IllegalArgumentException("external message fields are required");
            content = require(content, "content", 16_384);
            if (requestedLifetime.isZero() || requestedLifetime.isNegative() || requestedLifetime.compareTo(Duration.ofMinutes(5)) > 0)
                throw new IllegalArgumentException("external message lifetime must be 1ns..5m");
        }
    }

    record ExternalPublishResult(PublishStatus status, UUID messageId, boolean retryable, String diagnosticCode) {
        public ExternalPublishResult {
            if (status == null) throw new IllegalArgumentException("publish status is required");
            diagnosticCode = diagnosticCode == null ? "" : require(diagnosticCode, "diagnostic code", 128);
            if ((status == PublishStatus.ACCEPTED || status == PublishStatus.DUPLICATE) && messageId == null)
                throw new IllegalArgumentException("successful publish requires a message id");
        }
    }

    @FunctionalInterface interface AcceptedMessageListener { void onAccepted(AcceptedMessage message); }
    interface Subscription extends AutoCloseable { @Override void close(); }

    private static String require(String value, String label, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException(label + " is invalid");
        return value;
    }
}
