package com.example.im.attachment;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

final class AttachmentDtos {
    private AttachmentDtos() {}

    record CreateAttachmentRequest(
            @NotBlank @Size(max = 255) String fileName,
            @NotBlank @Size(max = 128) String mimeType,
            @Min(1) @Max(26_214_400) long sizeBytes,
            @Pattern(regexp = "(?i)^[0-9a-f]{64}$") String sha256
    ) {}

    record UploadTicket(UUID id, UUID conversationId, String status, String uploadUrl,
                        String completeUrl, Instant expiresAt) {}

    record AttachmentView(UUID id, UUID conversationId, UUID ownerId, String fileName,
                          String mimeType, long sizeBytes, String sha256, String status,
                          Long boundSeq, String downloadUrl, Instant expiresAt, Instant createdAt) {}
}
