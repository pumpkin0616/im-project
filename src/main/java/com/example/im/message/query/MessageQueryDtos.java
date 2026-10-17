package com.example.im.message.query;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class MessageQueryDtos {
    private MessageQueryDtos() {}

    record AttachmentView(UUID id, String fileName, String mimeType, long sizeBytes,
                          String sha256, String downloadUrl) {}

    record MessageView(UUID id, long seq, UUID senderId, UUID clientMsgId, String type,
                       JsonNode content, Instant createdAt, List<AttachmentView> attachments) {}

    record MessagePage(List<MessageView> items, String direction, boolean hasMore,
                       Long nextBeforeSeq, Long nextAfterSeq, long minAvailableSeq,
                       long visibleFromSeq, long highWatermark) {}
}
