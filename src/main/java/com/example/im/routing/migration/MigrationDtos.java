package com.example.im.routing.migration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class MigrationDtos {
    private MigrationDtos() {}

    enum ScopeType { SLOT, CONVERSATION }

    record StartMigrationRequest(
            @NotNull ScopeType scopeType,
            @Min(0) @Max(1023) Integer slotId,
            UUID conversationId,
            @NotBlank String targetShard,
            @Min(0) @Max(7) int targetTableGroup
    ) {}

    record MigrationView(
            UUID id,
            ScopeType scopeType,
            int slotId,
            UUID conversationId,
            String sourceShard,
            int sourceTableGroup,
            String targetShard,
            int targetTableGroup,
            long sourceEpoch,
            long targetEpoch,
            String state,
            Map<String, Object> checkpoint,
            String lastError,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant completedAt
    ) {}

    record RouteView(UUID conversationId, int slotId, String shardId, int tableGroup,
                     long routeEpoch, String state, String scope) {}

    record CacheView(long entries, long observedRouteVersion) {}
}
