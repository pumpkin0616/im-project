package com.example.im.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "im")
public record ImProperties(
        Routing routing,
        Message message,
        RocketMq rocketmq
) {
    public record Routing(int slotCount, int tableGroupCount, long hashSeed, String localShardId) {
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
        }
    }

    public record Message(Duration automaticRetryWindow, Duration maxClockSkew) {
    }

    public record RocketMq(
            boolean enabled,
            String endpoints,
            String messageTopic,
            String producerIdentity,
            int outboxBatchSize,
            int outboxMaxInFlight,
            Duration outboxLease,
            int outboxMaxAttempts
    ) {
        public RocketMq {
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

