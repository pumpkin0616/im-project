package com.example.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "im")
public record ImProperties(
        Routing routing,
        Message message,
        RocketMq rocketmq,
        Auth auth,
        Storage storage,
        Gateway gateway,
        Online online
) {
    public record Routing(
            int slotCount, int tableGroupCount, long hashSeed, String localShardId,
            String mode, Duration cacheTtl, Duration revisionPollInterval,
            int cacheMaxEntries, String adminSecret, int migrationBatchSize,
            Map<String, ShardConnection> migrationShards
    ) {
        public Routing {
            if (slotCount <= 0 || Integer.bitCount(slotCount) != 1) {
                throw new IllegalArgumentException("slotCount must be a positive power of two");
            }
            if (tableGroupCount <= 0) {
                throw new IllegalArgumentException("tableGroupCount must be positive");
            }
            if (localShardId == null || localShardId.isBlank()) {
                throw new IllegalArgumentException("localShardId must not be blank");
            }
            if (mode == null || mode.isBlank()) mode = "control-plane";
            if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
                throw new IllegalArgumentException("routing cacheTtl must be positive");
            }
            if (revisionPollInterval == null || revisionPollInterval.isNegative() || revisionPollInterval.isZero()) {
                throw new IllegalArgumentException("routing revisionPollInterval must be positive");
            }
            if (cacheMaxEntries <= 0) throw new IllegalArgumentException("routing cacheMaxEntries must be positive");
            if (adminSecret == null || adminSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16) {
                throw new IllegalArgumentException("routing adminSecret must contain at least 16 bytes");
            }
            if (migrationBatchSize <= 0 || migrationBatchSize > 10_000) {
                throw new IllegalArgumentException("routing migrationBatchSize must be between 1 and 10000");
            }
            migrationShards = migrationShards == null ? Map.of() : Map.copyOf(migrationShards);
        }
    }

    public record ShardConnection(String url, String username, String password, int maximumPoolSize) {
        public ShardConnection {
            if (url == null || url.isBlank()) throw new IllegalArgumentException("migration shard url is required");
            if (username == null || username.isBlank()) throw new IllegalArgumentException("migration shard username is required");
            if (maximumPoolSize <= 0) maximumPoolSize = 4;
        }
    }

    public record Message(Duration automaticRetryWindow, Duration maxClockSkew) {
    }

    public record Auth(String issuer, String jwtSecret, Duration accessTokenTtl, Duration refreshTokenTtl) {
        public Auth {
            if (jwtSecret == null || jwtSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 32) {
                throw new IllegalArgumentException("JWT secret must contain at least 32 bytes");
            }
        }
    }

    public record Storage(Path root, long maxAttachmentBytes, Duration uploadTtl) {
        public Storage {
            if (root == null) {
                throw new IllegalArgumentException("storage root must not be null");
            }
            if (maxAttachmentBytes <= 0) {
                throw new IllegalArgumentException("maxAttachmentBytes must be positive");
            }
            if (uploadTtl == null || uploadTtl.isNegative() || uploadTtl.isZero()) {
                throw new IllegalArgumentException("uploadTtl must be positive");
            }
        }
    }

    public record Gateway(
            boolean enabled, int port, String publicUrl, List<String> allowedOrigins,
            Duration ticketTtl, Duration heartbeatInterval, Duration idleTimeout,
            Duration slowConnectionTimeout, int bossThreads, int workerThreads,
            int businessThreads, int businessQueueCapacity, int maxFrameBytes,
            int writeLowWatermarkBytes, int writeHighWatermarkBytes, int maxPendingOutboundBytes,
            int maxConnectionInFlight, int maxGatewayInFlight,
            int operationsPerSecond, int operationBurst
    ) {
        public Gateway {
            allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
            if (port < 1 || port > 65535) throw new IllegalArgumentException("gateway port is invalid");
            if (publicUrl == null || publicUrl.isBlank()) throw new IllegalArgumentException("gateway publicUrl is required");
            if (ticketTtl == null || ticketTtl.isNegative() || ticketTtl.isZero()) throw new IllegalArgumentException("gateway ticketTtl must be positive");
            if (heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.isZero()) throw new IllegalArgumentException("gateway heartbeatInterval must be positive");
            if (idleTimeout == null || idleTimeout.compareTo(heartbeatInterval) <= 0) throw new IllegalArgumentException("gateway idleTimeout must exceed heartbeatInterval");
            if (slowConnectionTimeout == null || slowConnectionTimeout.isNegative() || slowConnectionTimeout.isZero()) throw new IllegalArgumentException("gateway slowConnectionTimeout must be positive");
            if (bossThreads <= 0 || workerThreads <= 0 || businessThreads <= 0 || businessQueueCapacity <= 0) throw new IllegalArgumentException("gateway thread and queue sizes must be positive");
            if (maxFrameBytes <= 0 || writeLowWatermarkBytes <= 0 || writeHighWatermarkBytes <= writeLowWatermarkBytes) throw new IllegalArgumentException("gateway frame and watermarks are invalid");
            if (maxPendingOutboundBytes <= writeHighWatermarkBytes) throw new IllegalArgumentException("gateway maxPendingOutboundBytes must exceed the high watermark");
            if (maxConnectionInFlight <= 0 || maxGatewayInFlight < maxConnectionInFlight) throw new IllegalArgumentException("gateway in-flight limits are invalid");
            if (operationsPerSecond <= 0 || operationBurst < operationsPerSecond) throw new IllegalArgumentException("gateway rate limits are invalid");
        }
    }

    public record Online(
            boolean enabled, String gatewayId, String dispatchUrl, String dispatchSecret,
            Duration leaseTtl, Duration renewInterval, int routeThreads, int routeQueueCapacity
    ) {
        public Online {
            if (gatewayId == null || gatewayId.isBlank()) throw new IllegalArgumentException("online gatewayId is required");
            if (dispatchUrl == null || dispatchUrl.isBlank()) throw new IllegalArgumentException("online dispatchUrl is required");
            if (dispatchSecret == null || dispatchSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 16) {
                throw new IllegalArgumentException("online dispatchSecret must contain at least 16 bytes");
            }
            if (leaseTtl == null || leaseTtl.isNegative() || leaseTtl.isZero()) throw new IllegalArgumentException("online leaseTtl must be positive");
            if (renewInterval == null || renewInterval.isNegative() || renewInterval.isZero()
                    || renewInterval.compareTo(leaseTtl) >= 0) throw new IllegalArgumentException("online renewInterval must be shorter than leaseTtl");
            if (routeThreads <= 0 || routeQueueCapacity <= 0) throw new IllegalArgumentException("online route executor limits must be positive");
        }
    }

    public record RocketMq(
            boolean enabled,
            String endpoints,
            String messageTopic,
            String membershipTopic,
            String producerIdentity,
            String consumerGroup,
            int consumerThreads,
            int outboxBatchSize,
            int outboxMaxInFlight,
            Duration outboxLease,
            int outboxMaxAttempts
    ) {
        public RocketMq {
            if (consumerGroup == null || consumerGroup.isBlank()) throw new IllegalArgumentException("consumerGroup is required");
            if (consumerThreads <= 0) throw new IllegalArgumentException("consumerThreads must be positive");
            if (outboxBatchSize <= 0 || outboxBatchSize > 1000) {
                throw new IllegalArgumentException("outboxBatchSize must be between 1 and 1000");
            }
            if (outboxMaxInFlight < outboxBatchSize) {
                throw new IllegalArgumentException("outboxMaxInFlight must be at least outboxBatchSize");
            }
            if (outboxLease == null || outboxLease.isNegative() || outboxLease.isZero()) {
                throw new IllegalArgumentException("outboxLease must be positive");
            }
            if (outboxMaxAttempts <= 0) {
                throw new IllegalArgumentException("outboxMaxAttempts must be positive");
            }
        }
    }
}

