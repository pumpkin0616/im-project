package com.example.im.message.api;

import com.example.im.message.application.SendMessageHandler;
import com.example.im.message.domain.SendMessageCommand;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/messages")
public final class MessageController {
    private final SendMessageHandler handler;

    public MessageController(SendMessageHandler handler) {
        this.handler = handler;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SendMessageResponse send(
            @RequestHeader("X-User-Id") UUID authenticatedUserId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        // X-User-Id is a temporary PoC authentication boundary. The production
        // gateway will replace it with a signed internal identity assertion.
        SendMessageCommand command = new SendMessageCommand(
                request.conversationId(),
                authenticatedUserId,
                request.clientMsgId(),
                request.type(),
                request.content(),
                request.attachmentIds()
        );
        return SendMessageResponse.from(handler.handle(command));
    }
}

