package com.example.im.message.query;

import com.example.im.message.query.MessageQueryDtos.MessagePage;
import com.example.im.message.query.MessageQueryDtos.MessageView;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/messages")
public final class MessageQueryController {
    private final MessageQueryService service;

    public MessageQueryController(MessageQueryService service) {
        this.service = service;
    }

    @GetMapping
    MessagePage list(@AuthenticationPrincipal Jwt jwt,
                     @PathVariable UUID conversationId,
                     @RequestParam(required = false) Long beforeSeq,
                     @RequestParam(required = false) Long afterSeq,
                     @RequestParam(defaultValue = "50") int limit) {
        return service.list(user(jwt), conversationId, beforeSeq, afterSeq,
                Math.max(1, Math.min(limit, 100)));
    }

    @GetMapping("/{seq}")
    MessageView get(@AuthenticationPrincipal Jwt jwt,
                    @PathVariable UUID conversationId,
                    @PathVariable long seq) {
        return service.get(user(jwt), conversationId, seq);
    }

    private UUID user(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
