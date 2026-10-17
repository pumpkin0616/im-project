package com.example.im.message.api;

import com.example.im.message.domain.MessageType;
import tools.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record SendMessageRequest(
        @NotNull UUID conversationId,
        @NotNull UUID clientMsgId,
        @NotNull MessageType type,
        @NotNull JsonNode content,
        List<UUID> attachmentIds
) {
}

