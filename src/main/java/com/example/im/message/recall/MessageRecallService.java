package com.example.im.message.recall;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.message.recall.MessageRecallDtos.RecallView;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import com.example.im.routing.RouteWriteGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
final class MessageRecallService {
    private static final Duration WINDOW = Duration.ofMinutes(2);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationRouteResolver routes;
    private final UuidV7Generator ids;
    private final ObjectMapper objectMapper;
    private final String localShard;
    private final RouteWriteGuard routeWriteGuard;

    MessageRecallService(JdbcTemplate jdbc, TransactionTemplate transactions,
                         ConversationRouteResolver routes, UuidV7Generator ids,
                         ObjectMapper objectMapper, ImProperties properties,
                         RouteWriteGuard routeWriteGuard) {
        this.jdbc = jdbc; this.transactions = transactions; this.routes = routes;
        this.ids = ids; this.objectMapper = objectMapper;
        this.localShard = properties.routing().localShardId();
        this.routeWriteGuard = routeWriteGuard;
    }

    RecallView recall(UUID actor, UUID conversationId, long sequence) {
        if (sequence < 1) throw error("MESSAGE_NOT_FOUND", "Message does not exist", 404);
        ResolvedRoute route = routes.resolve(conversationId);
        if (!localShard.equals(route.shardId())) throw error("ROUTE_MISMATCH", "Conversation belongs to " + route.shardId(), 503);
        MessageTableNames tables = new MessageTableNames(route.tableGroup(), 0);
        return transactions.execute(status -> recallInTransaction(actor, conversationId, sequence, route, tables));
    }

    private RecallView recallInTransaction(UUID actor, UUID conversationId, long sequence,
                                           ResolvedRoute route, MessageTableNames tables) {
        routeWriteGuard.lock(jdbc, route, conversationId);
        List<Integer> conversations = jdbc.query("SELECT status FROM " + tables.conversations()
                        + " WHERE id=? FOR UPDATE", (rs, n) -> rs.getInt(1), bytes(conversationId));
        if (conversations.isEmpty()) throw error("MESSAGE_NOT_FOUND", "Message does not exist", 404);
        if (conversations.getFirst() != 1) throw error("CONVERSATION_NOT_ACTIVE", "Conversation is not active", 422);

        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM " + tables.members()
                        + " WHERE conversation_id=? AND user_id=? AND status=1", Integer.class,
                bytes(conversationId), bytes(actor));
        if (active == null || active == 0) throw error("MESSAGE_RECALL_FORBIDDEN", "Only an active member can recall a message", 403);

        List<StoredMessage> rows = jdbc.query("SELECT message_id,message_day,sender_id,status,created_at,recalled_by,recalled_at FROM "
                        + tables.messages() + " WHERE conversation_id=? AND seq=? FOR UPDATE",
                (rs, n) -> new StoredMessage(UuidCodec.fromBytes(rs.getBytes("message_id")),
                        rs.getDate("message_day"), UuidCodec.fromBytes(rs.getBytes("sender_id")),
                        rs.getInt("status"), rs.getTimestamp("created_at").toInstant(),
                        uuid(rs.getBytes("recalled_by")), instant(rs.getTimestamp("recalled_at"))),
                bytes(conversationId), sequence);
        if (rows.isEmpty()) throw error("MESSAGE_NOT_FOUND", "Message does not exist", 404);
        StoredMessage message = rows.getFirst();
        if (!message.senderId().equals(actor)) throw error("MESSAGE_RECALL_FORBIDDEN", "Only the sender can recall this message", 403);
        if (message.status() == 2) {
            return new RecallView(conversationId, message.messageId(), sequence,
                    message.recalledBy(), message.recalledAt(), "RECALLED");
        }

        LocalDateTime databaseNow = jdbc.queryForObject("SELECT UTC_TIMESTAMP(3)", LocalDateTime.class);
        if (databaseNow == null) throw new IllegalStateException("Database returned no current time");
        Instant now = databaseNow.toInstant(ZoneOffset.UTC);
        if (now.isAfter(message.createdAt().plus(WINDOW))) {
            throw error("MESSAGE_RECALL_EXPIRED", "The two-minute recall window has expired", 422);
        }
        int changed = jdbc.update("UPDATE " + tables.messages()
                        + " SET status=2,recalled_by=?,recalled_at=? WHERE conversation_id=? AND seq=? AND status=1",
                bytes(actor), Timestamp.valueOf(databaseNow), bytes(conversationId), sequence);
        if (changed != 1) throw new IllegalStateException("Message recall update affected " + changed + " rows");

        UUID eventId = ids.next();
        String payload = json(Map.of("messageId", message.messageId().toString(),
                "recalledBy", actor.toString(), "recalledAt", now.toString()));
        jdbc.update("INSERT INTO " + tables.outbox()
                        + "(event_id,event_type,schema_version,slot_id,conversation_id,message_day,message_seq,payload,status,available_at,created_at) "
                        + "VALUES(?,'MESSAGE_RECALLED',1,?,?,?,?,CAST(? AS JSON),0,?,?)",
                bytes(eventId), route.slotId(), bytes(conversationId), message.messageDay(), sequence,
                payload, Timestamp.valueOf(databaseNow), Timestamp.valueOf(databaseNow));
        return new RecallView(conversationId, message.messageId(), sequence, actor, now, "RECALLED");
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Cannot encode recall event", exception); }
    }
    private byte[] bytes(UUID value) { return UuidCodec.toBytes(value); }
    private UUID uuid(byte[] value) { return value == null ? null : UuidCodec.fromBytes(value); }
    private Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private ApiBusinessException error(String code, String message, int status) { return new ApiBusinessException(code, message, status); }
    private record StoredMessage(UUID messageId, Date messageDay, UUID senderId, int status,
                                 Instant createdAt, UUID recalledBy, Instant recalledAt) {}
}
