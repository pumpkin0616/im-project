package com.example.im.message.domain;

import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record SendMessageCommand(
        UUID conversationId,
        UUID senderId,
        UUID clientMessageId,
        MessageType type,
        JsonNode content,
        List<UUID> attachmentIds
) {
    public SendMessageCommand {
        attachmentIds = attachmentIds == null ? List.of() : List.copyOf(attachmentIds);
    }
}

