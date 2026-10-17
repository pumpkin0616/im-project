package com.example.im.delivery;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record DeliveryMessage(UUID conversationId, UUID messageId, long sequence, UUID senderId,
                       UUID clientMessageId, String type, JsonNode content, Instant createdAt,
                       List<Map<String, Object>> attachments, List<UUID> recipients) {
    Map<String, Object> payload() {
        return Map.ofEntries(
                Map.entry("conversationId", conversationId.toString()),
                Map.entry("messageId", messageId.toString()),
                Map.entry("seq", Long.toUnsignedString(sequence)),
                Map.entry("senderId", senderId.toString()),
                Map.entry("clientMsgId", clientMessageId.toString()),
                Map.entry("type", type), Map.entry("content", content),
                Map.entry("createdAt", createdAt.toString()), Map.entry("attachments", attachments));
    }
}
