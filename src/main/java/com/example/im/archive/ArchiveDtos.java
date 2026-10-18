package com.example.im.archive;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

final class ArchiveDtos {
    private ArchiveDtos() {}

    record ArchiveRequest(@Min(0) @Max(7) int tableGroup,
                          @Min(0) @Max(1023) int slotId,
                          @NotNull LocalDate messageDay) {}

    record ArchiveBatchView(UUID id, int tableGroup, int slotId, LocalDate messageDay,
                            int generation, String state, long rowCount, String manifestKey,
                            String manifestSha256, String lastError, Instant createdAt,
                            Instant verifiedAt, Instant publishedAt) {}

    record CleanupView(UUID runId, Map<String, Long> deleted, Instant completedAt) {}
}
