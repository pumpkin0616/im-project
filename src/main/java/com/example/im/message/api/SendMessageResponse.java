package com.example.im.message.api;

import com.example.im.message.domain.SendMessageResult;

import java.time.Instant;
import java.util.UUID;

public record SendMessageResponse(
        UUID conversationId,
        UUID clientMsgId,
        UUID messageId,
        String seq,
        Instant serverTime,
        boolean replayed
) {
    static SendMessageResponse from(SendMessageResult result) {
        return new SendMessageResponse(
                result.conversationId(),
                result.clientMessageId(),
                result.messageId(),
                Long.toUnsignedString(result.sequence()),
                result.serverTime(),
                result.replayed()
        );
    }
}

