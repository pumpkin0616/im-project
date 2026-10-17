package com.example.im.message.recall;

import com.example.im.message.recall.MessageRecallDtos.RecallView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public final class MessageRecallController {
    private final MessageRecallService service;

    public MessageRecallController(MessageRecallService service) { this.service = service; }

    @PostMapping("/{seq}/recall")
    RecallView recall(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
                      @PathVariable long seq) {
        return service.recall(UUID.fromString(jwt.getSubject()), conversationId, seq);
    }
}
