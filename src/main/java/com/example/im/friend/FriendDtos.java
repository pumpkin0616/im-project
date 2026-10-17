package com.example.im.friend;

import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

final class FriendDtos {
    private FriendDtos() {}
    record CreateRequest(UUID recipientId, @Size(max=256) String note) {}
    record RequestView(UUID id, UUID requesterId, String requesterAccount, String requesterNickname,
                       UUID recipientId, String recipientAccount, String recipientNickname,
                       int status, String note, Instant createdAt, Instant handledAt) {}
    record FriendView(UUID id, String account, String nickname, String avatarKey, Instant friendsSince) {}
    record BlockView(UUID id, String account, String nickname, String avatarKey, Instant blockedAt) {}
    record UserSearchView(UUID id, String account, String nickname, String avatarKey,
                          String relationship, UUID pendingRequestId, boolean friend,
                          boolean pendingIncoming, boolean pendingOutgoing, boolean blockedByMe) {}
}
