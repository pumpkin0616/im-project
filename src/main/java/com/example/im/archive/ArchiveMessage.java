package com.example.im.archive;

import java.time.Instant;
import java.util.UUID;

public record ArchiveMessage(UUID conversationId, long seq, UUID messageId, UUID senderId, UUID clientMsgId,
                             int type, int status, String content, UUID recalledBy, Instant recalledAt,
                             Instant createdAt) {}
