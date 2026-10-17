package com.example.im.message.recall;

import java.time.Instant;
import java.util.UUID;

final class MessageRecallDtos {
    private MessageRecallDtos() {}

    record RecallView(UUID conversationId, UUID messageId, long seq, UUID recalledBy,
                      Instant recalledAt, String status) {}
}
