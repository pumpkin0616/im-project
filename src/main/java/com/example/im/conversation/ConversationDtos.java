package com.example.im.conversation;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class ConversationDtos {
    private ConversationDtos() {}
    record CreateDirectRequest(@NotNull UUID peerUserId) {}
    record CreateGroupRequest(@NotBlank @Size(max=128) String title,
                              @NotEmpty @Size(max=499) List<UUID> memberIds) {}
    record UpdateConversationRequest(@NotBlank @Size(max=128) String title) {}
    record AddMembersRequest(@NotEmpty @Size(max=100) List<UUID> userIds) {}
    record UpdateRoleRequest(@NotNull Role role) {}
    enum Role { MEMBER, ADMIN }
    record MemberView(UUID userId, String account, String nickname, String avatarKey,
                      String role, long joinedSeq, long lastReadSeq, Instant joinedAt) {}
    record PeerView(UUID userId, String account, String nickname, String avatarUrl) {}
    record LastMessageView(long seq, String type, String status, UUID senderId,
                           String summary, Instant createdAt) {}
    record ConversationView(UUID id, String type, String title, UUID ownerId, String status,
                            String myRole, long lastSeq, long minAvailableSeq, long memberVersion,
                            int memberCount, Instant lastMessageAt, Instant createdAt,
                            List<MemberView> members, long myLastReadSeq, long unreadCount,
                            LastMessageView lastMessage, PeerView peer) {}
    record ConversationPage(List<ConversationView> items, boolean hasMore, String nextCursor) {}
}
