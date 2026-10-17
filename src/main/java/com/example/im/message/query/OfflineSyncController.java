package com.example.im.message.query;

import com.example.im.message.query.MessageQueryDtos.MessagePage;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/sync")
public final class OfflineSyncController {
    private final MessageQueryService messages;

    public OfflineSyncController(MessageQueryService messages) { this.messages = messages; }

    @PostMapping
    SyncResponse sync(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody SyncRequest request) {
        UUID userId = UUID.fromString(jwt.getSubject());
        int limit = request.limitPerConversation() == null ? 50 : request.limitPerConversation();
        List<ConversationDelta> values = request.conversations().stream()
                .map(cursor -> new ConversationDelta(cursor.conversationId(), cursor.afterSeq(),
                        messages.list(userId, cursor.conversationId(), null, cursor.afterSeq(), limit)))
                .toList();
        return new SyncResponse(Instant.now(), values);
    }

    public record SyncRequest(
            @NotEmpty @Size(max = 20) List<@Valid ConversationCursor> conversations,
            @Min(1) @Max(100) Integer limitPerConversation) {}
    public record ConversationCursor(@NotNull UUID conversationId, @PositiveOrZero long afterSeq) {}
    public record ConversationDelta(UUID conversationId, long requestedAfterSeq, MessagePage page) {}
    public record SyncResponse(Instant serverTime, List<ConversationDelta> conversations) {}
}
