package com.example.im.message.infrastructure;

import com.example.im.config.ImProperties;
import com.example.im.message.application.MessageTransactionPort;
import com.example.im.message.domain.MessageRejectedException;
import com.example.im.message.domain.SendMessageCommand;
import com.example.im.message.domain.SendMessageResult;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

@Repository
public class JdbcMessageTransactionAdapter implements MessageTransactionPort {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;
    private final UuidV7Generator uuidGenerator;
    private final String localShardId;

    public JdbcMessageTransactionAdapter(
            JdbcTemplate jdbc,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper,
            UuidV7Generator uuidGenerator,
            ImProperties properties
    ) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
        this.uuidGenerator = uuidGenerator;
        this.localShardId = properties.routing().localShardId();
    }

    @Override
    public SendMessageResult execute(
            ResolvedRoute route,
            SendMessageCommand command,
            byte[] requestHash,
            long dedupGeneration,
            Instant requestTime
    ) {
        if (!localShardId.equals(route.shardId())) {
            throw new MessageRejectedException("ROUTE_MISMATCH",
                    "Conversation belongs to " + route.shardId(), true);
        }
        MessageTableNames tables = new MessageTableNames(route.tableGroup(), dedupGeneration);
        for (int attempt = 1; ; attempt++) {
            try {
                SendMessageResult result = transactionTemplate.execute(status ->
                        executeInTransaction(route, command, requestHash, tables));
                if (result == null) {
                    throw new IllegalStateException("Message transaction returned no result");
                }
                return result;
            } catch (TransientDataAccessException exception) {
                if (attempt >= 3) {
                    throw exception;
                }
                long backoffMillis = ThreadLocalRandom.current().nextLong(2, 9) * attempt;
                LockSupport.parkNanos(backoffMillis * 1_000_000L);
            }
        }
    }

    private SendMessageResult executeInTransaction(
            ResolvedRoute route,
            SendMessageCommand command,
            byte[] requestHash,
            MessageTableNames tables
    ) {
        lockDedupGeneration(tables.dedupGeneration());
        lockSlot(route);
        ConversationState conversation = lockConversation(tables, command.conversationId());
        validateConversation(route, conversation);

        ExistingMessage existing = findExisting(tables, command);
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.requestHash(), requestHash)) {
                throw new MessageRejectedException("IDEMPOTENCY_CONFLICT",
                        "clientMsgId was already used with different content", false);
            }
            return existing.toResult(command, true);
        }

        requireActiveMember(tables, command);
        lockAndValidateAttachments(tables, command);

        LocalDateTime databaseNow = jdbc.queryForObject(
                "SELECT UTC_TIMESTAMP(3)", LocalDateTime.class);
        if (databaseNow == null) {
            throw new IllegalStateException("Database returned no current time");
        }
        LocalDateTime serverTime = conversation.lastMessageAt() == null
                || databaseNow.isAfter(conversation.lastMessageAt())
                ? databaseNow : conversation.lastMessageAt();
        long sequence = Math.addExact(conversation.lastSequence(), 1);
        LocalDate messageDay = serverTime.toLocalDate();
        UUID messageId = uuidGenerator.next();
        UUID eventId = uuidGenerator.next();

        updateConversation(tables, command.conversationId(), sequence, serverTime);
        insertMessage(tables, route.slotId(), command, messageId, sequence, messageDay, serverTime);
        insertDedup(tables, route.slotId(), command, requestHash,
                messageId, sequence, messageDay, serverTime);
        bindAttachments(tables, command, sequence);
        insertOutbox(tables, route.slotId(), command.conversationId(), messageId,
                eventId, sequence, messageDay, serverTime);

        return new SendMessageResult(command.conversationId(), command.clientMessageId(),
                messageId, sequence, serverTime.toInstant(ZoneOffset.UTC), false);
    }

    private void lockDedupGeneration(long generation) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT state FROM dedup_generations WHERE generation = ? AND state = 1 "
                        + "AND accept_until >= UTC_TIMESTAMP(3) FOR SHARE",
                generation);
        if (rows.isEmpty()) {
            throw new MessageRejectedException("DEDUP_GENERATION_UNAVAILABLE",
                    "The idempotency generation is not open", true);
        }
    }

    private void lockSlot(ResolvedRoute route) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT route_epoch, writable FROM slot_fences WHERE slot_id = ? FOR SHARE",
                route.slotId());
        if (rows.isEmpty()) {
            throw new MessageRejectedException("ROUTE_MISMATCH", "Slot fence is missing", true);
        }
        Map<String, Object> row = rows.getFirst();
        long epoch = ((Number) row.get("route_epoch")).longValue();
        Object rawWritable = row.get("writable");
        boolean writable = rawWritable instanceof Boolean value
                ? value : ((Number) rawWritable).intValue() != 0;
        if (!writable || epoch != route.routeEpoch()) {
            throw new MessageRejectedException("ROUTE_STALE", "Slot route is not writable", true);
        }
    }

    private ConversationState lockConversation(MessageTableNames tables, UUID conversationId) {
        String sql = "SELECT route_epoch, write_state, status, last_seq, last_message_at "
                + "FROM " + tables.conversations() + " WHERE id = ? FOR UPDATE";
        List<ConversationState> rows = jdbc.query(sql,
                (rs, rowNum) -> new ConversationState(
                        rs.getLong("route_epoch"),
                        rs.getInt("write_state"),
                        rs.getInt("status"),
                        rs.getLong("last_seq"),
                        rs.getTimestamp("last_message_at") == null
                                ? null : rs.getTimestamp("last_message_at").toLocalDateTime()),
                UuidCodec.toBytes(conversationId));
        if (rows.isEmpty()) {
            throw new MessageRejectedException("CONVERSATION_NOT_FOUND",
                    "Conversation does not exist", false);
        }
        return rows.getFirst();
    }

    private static void validateConversation(ResolvedRoute route, ConversationState conversation) {
        if (conversation.routeEpoch() != route.routeEpoch() || conversation.writeState() != 1) {
            throw new MessageRejectedException("ROUTE_STALE", "Conversation has moved or is frozen", true);
        }
        if (conversation.status() != 1) {
            throw new MessageRejectedException("CONVERSATION_CLOSED", "Conversation is not active", false);
        }
    }

    private ExistingMessage findExisting(MessageTableNames tables, SendMessageCommand command) {
        String sql = "SELECT request_hash, message_id, seq, created_at FROM " + tables.dedup()
                + " WHERE conversation_id = ? AND sender_id = ? AND client_msg_id = ?";
        List<ExistingMessage> rows = jdbc.query(sql,
                (rs, rowNum) -> new ExistingMessage(
                        rs.getBytes("request_hash"),
                        UuidCodec.fromBytes(rs.getBytes("message_id")),
                        rs.getLong("seq"),
                        rs.getTimestamp("created_at").toInstant()),
                UuidCodec.toBytes(command.conversationId()),
                UuidCodec.toBytes(command.senderId()),
                UuidCodec.toBytes(command.clientMessageId()));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void requireActiveMember(MessageTableNames tables, SendMessageCommand command) {
        String sql = "SELECT status FROM " + tables.members()
                + " WHERE conversation_id = ? AND user_id = ?";
        List<Integer> rows = jdbc.query(sql, (rs, rowNum) -> rs.getInt("status"),
                UuidCodec.toBytes(command.conversationId()), UuidCodec.toBytes(command.senderId()));
        if (rows.isEmpty() || rows.getFirst() != 1) {
            throw new MessageRejectedException("NOT_A_MEMBER",
                    "Sender is not an active conversation member", false);
        }
    }

    private void lockAndValidateAttachments(MessageTableNames tables, SendMessageCommand command) {
        List<UUID> sorted = command.attachmentIds().stream().sorted().toList();
        for (UUID attachmentId : sorted) {
            String sql = "SELECT conversation_id, owner_id, status, bound_seq FROM "
                    + tables.attachments() + " WHERE id = ? FOR UPDATE";
            List<AttachmentState> rows = jdbc.query(sql,
                    (rs, rowNum) -> new AttachmentState(
                            UuidCodec.fromBytes(rs.getBytes("conversation_id")),
                            UuidCodec.fromBytes(rs.getBytes("owner_id")),
                            rs.getInt("status"),
                            rs.getObject("bound_seq") == null ? null : rs.getLong("bound_seq")),
                    UuidCodec.toBytes(attachmentId));
            if (rows.isEmpty()) {
                throw new MessageRejectedException("ATTACHMENT_NOT_FOUND", "Attachment does not exist", false);
            }
            AttachmentState value = rows.getFirst();
            if (!value.conversationId().equals(command.conversationId())
                    || !value.ownerId().equals(command.senderId())
                    || value.status() != 2 || value.boundSequence() != null) {
                throw new MessageRejectedException("ATTACHMENT_NOT_READY",
                        "Attachment is not available for this message", false);
            }
        }
    }

    private void updateConversation(MessageTableNames tables, UUID conversationId,
                                    long sequence, LocalDateTime serverTime) {
        String sql = "UPDATE " + tables.conversations()
                + " SET last_seq = ?, last_message_at = ?, updated_at = ? WHERE id = ?";
        requireOneRow(jdbc.update(sql, sequence, Timestamp.valueOf(serverTime),
                Timestamp.valueOf(serverTime), UuidCodec.toBytes(conversationId)));
    }

    private void insertMessage(MessageTableNames tables, int slotId, SendMessageCommand command,
                               UUID messageId, long sequence, LocalDate day, LocalDateTime serverTime) {
        String sql = "INSERT INTO " + tables.messages()
                + " (conversation_id, message_day, seq, slot_id, message_id, sender_id, client_msg_id, "
                + "type, content, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), ?)";
        requireOneRow(jdbc.update(sql,
                UuidCodec.toBytes(command.conversationId()), Date.valueOf(day), sequence, slotId,
                UuidCodec.toBytes(messageId), UuidCodec.toBytes(command.senderId()),
                UuidCodec.toBytes(command.clientMessageId()), command.type().databaseCode(),
                json(command.content()), Timestamp.valueOf(serverTime)));
    }

    private void insertDedup(MessageTableNames tables, int slotId, SendMessageCommand command,
                             byte[] requestHash, UUID messageId, long sequence,
                             LocalDate day, LocalDateTime serverTime) {
        String sql = "INSERT INTO " + tables.dedup()
                + " (conversation_id, sender_id, client_msg_id, slot_id, request_hash, message_id, "
                + "seq, message_day, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        requireOneRow(jdbc.update(sql,
                UuidCodec.toBytes(command.conversationId()), UuidCodec.toBytes(command.senderId()),
                UuidCodec.toBytes(command.clientMessageId()), slotId, requestHash,
                UuidCodec.toBytes(messageId), sequence, Date.valueOf(day), Timestamp.valueOf(serverTime)));
    }

    private void bindAttachments(MessageTableNames tables, SendMessageCommand command, long sequence) {
        for (UUID attachmentId : command.attachmentIds()) {
            String sql = "UPDATE " + tables.attachments()
                    + " SET bound_seq = ? WHERE id = ? AND bound_seq IS NULL";
            requireOneRow(jdbc.update(sql, sequence, UuidCodec.toBytes(attachmentId)));
        }
    }

    private void insertOutbox(MessageTableNames tables, int slotId, UUID conversationId,
                              UUID messageId, UUID eventId, long sequence,
                              LocalDate day, LocalDateTime serverTime) {
        String payload = json(Map.of("messageId", messageId.toString()));
        String sql = "INSERT INTO " + tables.outbox()
                + " (event_id, event_type, schema_version, slot_id, conversation_id, message_day, "
                + "message_seq, payload, status, available_at, created_at) "
                + "VALUES (?, 'MESSAGE_CREATED', 1, ?, ?, ?, ?, CAST(? AS JSON), 0, ?, ?)";
        requireOneRow(jdbc.update(sql, UuidCodec.toBytes(eventId), slotId,
                UuidCodec.toBytes(conversationId), Date.valueOf(day), sequence, payload,
                Timestamp.valueOf(serverTime), Timestamp.valueOf(serverTime)));
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalArgumentException("Value cannot be serialized as JSON", exception);
        }
    }

    private static void requireOneRow(int affected) {
        if (affected != 1) {
            throw new IllegalStateException("Expected one affected row, got " + affected);
        }
    }

    private record ConversationState(
            long routeEpoch,
            int writeState,
            int status,
            long lastSequence,
            LocalDateTime lastMessageAt
    ) {
    }

    private record ExistingMessage(byte[] requestHash, UUID messageId, long sequence, Instant createdAt) {
        SendMessageResult toResult(SendMessageCommand command, boolean replayed) {
            return new SendMessageResult(command.conversationId(), command.clientMessageId(),
                    messageId, sequence, createdAt, replayed);
        }
    }

    private record AttachmentState(UUID conversationId, UUID ownerId, int status, Long boundSequence) {
    }
}

