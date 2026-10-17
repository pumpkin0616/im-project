package com.example.im.message.query;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MessageQueryDtos {
    private MessageQueryDtos() {}

    public record AttachmentView(UUID id, String fileName, String mimeType, long sizeBytes,
                          String sha256, String downloadUrl) {}

    public record MessageView(UUID id, long seq, UUID senderId, UUID clientMsgId, String type,
                       String status, JsonNode content, Instant createdAt,
                       UUID recalledBy, Instant recalledAt, List<AttachmentView> attachments) {}

    public record MessagePage(List<MessageView> items, String direction, boolean hasMore,
                       Long nextBeforeSeq, Long nextAfterSeq, long minAvailableSeq,
                       long visibleFromSeq, long highWatermark) {}
}
