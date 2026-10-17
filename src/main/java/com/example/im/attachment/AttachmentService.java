package com.example.im.attachment;

import com.example.im.attachment.AttachmentDtos.AttachmentView;
import com.example.im.attachment.AttachmentDtos.CreateAttachmentRequest;
import com.example.im.attachment.AttachmentDtos.UploadTicket;
import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
final class AttachmentService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationRouteResolver routes;
    private final UuidV7Generator ids;
    private final String localShard;
    private final Path root;
    private final long maximumBytes;
    private final java.time.Duration uploadTtl;

    AttachmentService(JdbcTemplate jdbc, TransactionTemplate transactions,
                      ConversationRouteResolver routes, UuidV7Generator ids,
                      ImProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.routes = routes;
        this.ids = ids;
        this.localShard = properties.routing().localShardId();
        this.root = properties.storage().root().toAbsolutePath().normalize();
        this.maximumBytes = properties.storage().maxAttachmentBytes();
        this.uploadTtl = properties.storage().uploadTtl();
    }

    UploadTicket create(UUID userId, UUID conversationId, CreateAttachmentRequest request) {
        if (request.sizeBytes() > maximumBytes) {
            throw error("ATTACHMENT_TOO_LARGE", "Attachment exceeds the configured size limit", 413);
        }
        Table table = table(conversationId);
        requireActiveMember(table, conversationId, userId);
        UUID id = ids.next();
        Instant now = Instant.now();
        Instant expires = now.plus(uploadTtl);
        String objectKey = table.route().slotId() + "/" + conversationId + "/" + id;
        byte[] expectedHash = request.sha256() == null ? null : HexFormat.of().parseHex(request.sha256());
        jdbc.update("INSERT INTO " + table.attachments() + "(id,conversation_id,slot_id,owner_id,"
                        + "object_key,file_name,mime_type,size_bytes,sha256,status,expires_at,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,0,?,?)",
                bytes(id), bytes(conversationId), table.route().slotId(), bytes(userId), objectKey,
                request.fileName(), request.mimeType(), request.sizeBytes(), expectedHash,
                Timestamp.from(expires), Timestamp.from(now));
        String base = "/api/v1/conversations/" + conversationId + "/attachments/" + id;
        return new UploadTicket(id, conversationId, "UPLOADING", base + "/content",
                base + "/complete", expires);
    }

    void upload(UUID userId, UUID conversationId, UUID attachmentId,
                long contentLength, InputStream input) throws IOException {
        Table table = table(conversationId);
        Attachment attachment = transactions.execute(status -> {
            Attachment value = attachmentForUpdate(table, conversationId, attachmentId);
            requireOwner(value, userId);
            if (value.status() != 0) throw error("UPLOAD_ALREADY_STARTED", "Attachment upload is not pending", 409);
            if (value.expiresAt().isBefore(Instant.now())) {
                jdbc.update("UPDATE " + table.attachments() + " SET status=4 WHERE id=?", bytes(attachmentId));
                throw error("UPLOAD_EXPIRED", "Attachment upload ticket has expired", 410);
            }
            if (contentLength >= 0 && contentLength != value.sizeBytes()) {
                throw error("SIZE_MISMATCH", "Uploaded content length does not match sizeBytes", 422);
            }
            jdbc.update("UPDATE " + table.attachments() + " SET status=1 WHERE id=?", bytes(attachmentId));
            return value;
        });
        if (attachment == null) throw new IllegalStateException("Attachment transaction returned no value");

        Path destination = objectPath(attachment.objectKey());
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), attachmentId + "-", ".upload");
        try {
            DigestResult result = copy(input, temporary, attachment.sizeBytes());
            if (result.size() != attachment.sizeBytes()) {
                reject(table, attachmentId);
                throw error("SIZE_MISMATCH", "Uploaded content does not match sizeBytes", 422);
            }
            if (attachment.sha256() != null && !MessageDigest.isEqual(attachment.sha256(), result.sha256())) {
                reject(table, attachmentId);
                throw error("HASH_MISMATCH", "Uploaded content does not match sha256", 422);
            }
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(temporary);
            if (exception instanceof ApiBusinessException) throw exception;
            reject(table, attachmentId);
            throw exception;
        }
    }

    AttachmentView complete(UUID userId, UUID conversationId, UUID attachmentId) {
        Table table = table(conversationId);
        Attachment initial = attachment(table, conversationId, attachmentId);
        requireOwner(initial, userId);
        if (initial.status() == 2) return view(initial, conversationId);
        if (initial.status() != 1) throw error("UPLOAD_NOT_FINISHED", "Attachment content has not been uploaded", 409);
        Path path = objectPath(initial.objectKey());
        if (!Files.isRegularFile(path)) throw error("UPLOAD_NOT_FINISHED", "Uploaded object is not available", 409);
        DigestResult verified;
        try {
            verified = digest(path);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot verify uploaded attachment", exception);
        }
        if (verified.size() != initial.sizeBytes()
                || (initial.sha256() != null && !MessageDigest.isEqual(initial.sha256(), verified.sha256()))) {
            jdbc.update("UPDATE " + table.attachments() + " SET status=3 WHERE id=? AND status=1", bytes(attachmentId));
            throw error("ATTACHMENT_REJECTED", "Attachment integrity verification failed", 422);
        }
        return transactions.execute(status -> {
            Attachment value = attachmentForUpdate(table, conversationId, attachmentId);
            requireOwner(value, userId);
            if (value.status() == 2) return view(value, conversationId);
            if (value.status() != 1) throw error("UPLOAD_NOT_FINISHED", "Attachment is no longer awaiting verification", 409);
                jdbc.update("UPDATE " + table.attachments() + " SET status=2,sha256=? WHERE id=?",
                        verified.sha256(), bytes(attachmentId));
                return view(new Attachment(value.id(), value.ownerId(), value.objectKey(), value.fileName(),
                        value.mimeType(), value.sizeBytes(), verified.sha256(), 2, value.boundSeq(),
                        value.expiresAt(), value.createdAt()), conversationId);
        });
    }

    AttachmentView get(UUID userId, UUID conversationId, UUID attachmentId) {
        Table table = table(conversationId);
        requireMember(table, conversationId, userId);
        Attachment value = attachment(table, conversationId, attachmentId);
        requireVisible(table, conversationId, value, userId);
        return view(value, conversationId);
    }

    Download download(UUID userId, UUID conversationId, UUID attachmentId) {
        Table table = table(conversationId);
        requireMember(table, conversationId, userId);
        Attachment value = attachment(table, conversationId, attachmentId);
        requireVisible(table, conversationId, value, userId);
        if (value.status() != 2) throw error("ATTACHMENT_NOT_READY", "Attachment is not ready", 409);
        Path path = objectPath(value.objectKey());
        if (!Files.isRegularFile(path)) throw error("ATTACHMENT_CONTENT_MISSING", "Attachment content is unavailable", 503);
        return new Download(path, value.fileName(), value.mimeType(), value.sizeBytes());
    }

    private void requireVisible(Table table, UUID conversationId, Attachment value, UUID userId) {
        if (value.boundSeq() == null && !value.ownerId().equals(userId)) {
            throw error("ATTACHMENT_NOT_FOUND", "Attachment does not exist", 404);
        }
        if (value.boundSeq() != null) {
            Integer recalled = jdbc.queryForObject("SELECT COUNT(*) FROM " + table.messages()
                            + " WHERE conversation_id=? AND seq=? AND status=2", Integer.class,
                    bytes(conversationId), value.boundSeq());
            if (recalled != null && recalled > 0) {
                throw error("ATTACHMENT_NOT_FOUND", "Attachment does not exist", 404);
            }
        }
    }

    private Attachment attachment(Table table, UUID conversationId, UUID attachmentId) {
        List<Attachment> values = jdbc.query(selectAttachment(table) + " WHERE id=? AND conversation_id=?",
                (rs, n) -> map(rs), bytes(attachmentId), bytes(conversationId));
        if (values.isEmpty()) throw error("ATTACHMENT_NOT_FOUND", "Attachment does not exist", 404);
        return values.getFirst();
    }

    private Attachment attachmentForUpdate(Table table, UUID conversationId, UUID attachmentId) {
        List<Attachment> values = jdbc.query(selectAttachment(table) + " WHERE id=? AND conversation_id=? FOR UPDATE",
                (rs, n) -> map(rs), bytes(attachmentId), bytes(conversationId));
        if (values.isEmpty()) throw error("ATTACHMENT_NOT_FOUND", "Attachment does not exist", 404);
        return values.getFirst();
    }

    private String selectAttachment(Table table) {
        return "SELECT id,owner_id,object_key,file_name,mime_type,size_bytes,sha256,status,bound_seq,expires_at,created_at FROM "
                + table.attachments();
    }

    private Attachment map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Attachment(UuidCodec.fromBytes(rs.getBytes("id")), UuidCodec.fromBytes(rs.getBytes("owner_id")),
                rs.getString("object_key"), rs.getString("file_name"), rs.getString("mime_type"),
                rs.getLong("size_bytes"), rs.getBytes("sha256"), rs.getInt("status"),
                rs.getObject("bound_seq") == null ? null : rs.getLong("bound_seq"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant());
    }

    private void requireActiveMember(Table table, UUID conversationId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table.members()
                        + " m JOIN " + table.conversations() + " c ON c.id=m.conversation_id "
                        + "WHERE m.conversation_id=? AND m.user_id=? AND m.status=1 AND c.status=1",
                Integer.class, bytes(conversationId), bytes(userId));
        if (count == null || count == 0) throw error("NOT_A_MEMBER", "User is not an active conversation member", 403);
    }

    private void requireMember(Table table, UUID conversationId, UUID userId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table.members()
                        + " WHERE conversation_id=? AND user_id=? AND status=1",
                Integer.class, bytes(conversationId), bytes(userId));
        if (count == null || count == 0) throw error("NOT_A_MEMBER", "User is not an active conversation member", 403);
    }

    private void requireOwner(Attachment attachment, UUID userId) {
        if (!attachment.ownerId().equals(userId)) throw error("ATTACHMENT_OWNER_REQUIRED", "Only the uploader can perform this operation", 403);
    }

    private AttachmentView view(Attachment value, UUID conversationId) {
        String status = switch (value.status()) {
            case 0 -> "UPLOADING"; case 1 -> "CHECKING"; case 2 -> "READY";
            case 3 -> "REJECTED"; case 4 -> "EXPIRED"; default -> "UNKNOWN";
        };
        String download = value.status() == 2 ? "/api/v1/conversations/" + conversationId
                + "/attachments/" + value.id() + "/content" : null;
        return new AttachmentView(value.id(), conversationId, value.ownerId(), value.fileName(),
                value.mimeType(), value.sizeBytes(), value.sha256() == null ? null
                : HexFormat.of().formatHex(value.sha256()), status, value.boundSeq(), download,
                value.expiresAt(), value.createdAt());
    }

    private DigestResult copy(InputStream input, Path target, long expected) throws IOException {
        MessageDigest digest = sha256();
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(target)) {
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count == 0) continue;
                total += count;
                if (total > expected || total > maximumBytes) throw error("ATTACHMENT_TOO_LARGE", "Uploaded content is too large", 413);
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        return new DigestResult(total, digest.digest());
    }

    private DigestResult digest(Path path) throws IOException {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = sha256(); long total = 0; byte[] buffer = new byte[64 * 1024];
            for (int count; (count = input.read(buffer)) >= 0; ) {
                if (count == 0) continue; total += count; digest.update(buffer, 0, count);
            }
            return new DigestResult(total, digest.digest());
        }
    }

    private MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private void reject(Table table, UUID id) {
        jdbc.update("UPDATE " + table.attachments() + " SET status=3 WHERE id=? AND status=1", bytes(id));
    }

    private Path objectPath(String objectKey) {
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) throw new IllegalStateException("Attachment object key escapes storage root");
        return path;
    }

    private Table table(UUID conversationId) {
        ResolvedRoute route = routes.resolve(conversationId);
        if (!route.shardId().equals(localShard)) throw error("ROUTE_MISMATCH", "Conversation belongs to " + route.shardId(), 503);
        MessageTableNames names = new MessageTableNames(route.tableGroup(), 0);
        return new Table(route, names.conversations(), names.members(), names.messages(), names.attachments());
    }

    private byte[] bytes(UUID value) { return UuidCodec.toBytes(value); }
    private ApiBusinessException error(String code, String message, int status) { return new ApiBusinessException(code, message, status); }

    record Download(Path path, String fileName, String mimeType, long sizeBytes) {}
    private record Table(ResolvedRoute route, String conversations, String members,
                         String messages, String attachments) {}
    private record Attachment(UUID id, UUID ownerId, String objectKey, String fileName,
                              String mimeType, long sizeBytes, byte[] sha256, int status,
                              Long boundSeq, Instant expiresAt, Instant createdAt) {}
    private record DigestResult(long size, byte[] sha256) {}
}
