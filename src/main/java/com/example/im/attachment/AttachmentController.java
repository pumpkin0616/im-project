package com.example.im.attachment;

import com.example.im.attachment.AttachmentDtos.AttachmentView;
import com.example.im.attachment.AttachmentDtos.CreateAttachmentRequest;
import com.example.im.attachment.AttachmentDtos.UploadTicket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations/{conversationId}/attachments")
public final class AttachmentController {
    private final AttachmentService service;

    public AttachmentController(AttachmentService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    UploadTicket create(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
                        @Valid @RequestBody CreateAttachmentRequest request) {
        return service.create(user(jwt), conversationId, request);
    }

    @PutMapping(path = "/{attachmentId}/content", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void upload(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
                @PathVariable UUID attachmentId, HttpServletRequest request) throws IOException {
        service.upload(user(jwt), conversationId, attachmentId,
                request.getContentLengthLong(), request.getInputStream());
    }

    @PostMapping("/{attachmentId}/complete")
    AttachmentView complete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
                            @PathVariable UUID attachmentId) {
        return service.complete(user(jwt), conversationId, attachmentId);
    }

    @GetMapping("/{attachmentId}")
    AttachmentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID conversationId,
                       @PathVariable UUID attachmentId) {
        return service.get(user(jwt), conversationId, attachmentId);
    }

    @GetMapping("/{attachmentId}/content")
    ResponseEntity<FileSystemResource> download(@AuthenticationPrincipal Jwt jwt,
                                                @PathVariable UUID conversationId,
                                                @PathVariable UUID attachmentId) {
        AttachmentService.Download value = service.download(user(jwt), conversationId, attachmentId);
        MediaType mediaType;
        try { mediaType = MediaType.parseMediaType(value.mimeType()); }
        catch (IllegalArgumentException ignored) { mediaType = MediaType.APPLICATION_OCTET_STREAM; }
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(value.fileName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(value.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(new FileSystemResource(value.path()));
    }

    private UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
}
