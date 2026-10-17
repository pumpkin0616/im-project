package com.example.im.message.domain;

import java.time.Instant;
import java.util.UUID;

public record SendMessageResult(
        UUID conversationId,
        UUID clientMessageId,
        UUID messageId,
        long sequence,
        Instant serverTime,
        boolean replayed
) {
}

