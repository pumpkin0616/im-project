package com.example.im.outbox;

import tools.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.util.UUID;

public record OutboxEvent(
        int tableGroup,
        long id,
        UUID eventId,
        String eventType,
        int schemaVersion,
        int slotId,
        UUID conversationId,
        LocalDate messageDay,
        Long messageSequence,
        JsonNode payload,
        int attempts
) {
}
