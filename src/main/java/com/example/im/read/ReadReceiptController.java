package com.example.im.read;

import jakarta.validation.Valid;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/read")
public final class ReadReceiptController {
    private final ReadReceiptService service;

    public ReadReceiptController(ReadReceiptService service) { this.service = service; }

    @PutMapping
    ReadReceiptService.ReadUpdate advance(@AuthenticationPrincipal Jwt jwt,
                                          @PathVariable UUID conversationId,
                                          @Valid @RequestBody ReadRequest request) {
        return service.advance(UUID.fromString(jwt.getSubject()), conversationId, request.seq());
    }

    record ReadRequest(@PositiveOrZero long seq) {}
}
