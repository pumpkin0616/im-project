package com.example.im.message.query;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.query.MessageQueryDtos.AttachmentView;
import com.example.im.message.query.MessageQueryDtos.MessagePage;
import com.example.im.message.query.MessageQueryDtos.MessageView;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
final class MessageQueryService {
    private final JdbcTemplate jdbc;
    private final ConversationRouteResolver routes;
    private final ObjectMapper objectMapper;
    private final String localShard;

    MessageQueryService(JdbcTemplate jdbc, ConversationRouteResolver routes,
                        ObjectMapper objectMapper, ImProperties properties) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.objectMapper = objectMapper;
        this.localShard = properties.routing().localShardId();
    }

    public MessagePage list(UUID userId, UUID conversationId, Long beforeSeq, Long afterSeq, int limit) {
        if (beforeSeq != null && afterSeq != null) {
            throw error("INVALID_CURSOR", "beforeSeq and afterSeq cannot be used together", 400);
        }
        if ((beforeSeq != null && beforeSeq < 1) || (afterSeq != null && afterSeq < 0)) {
            throw error("INVALID_CURSOR", "Message sequence cursor is invalid", 400);
        }

        Tables tables = tables(conversationId);
        Visibility visibility = visibility(tables, conversationId, userId);
        boolean forward = afterSeq != null;
        long cursor = forward ? afterSeq : beforeSeq == null ? visibility.highWatermark() + 1 : beforeSeq;
        String comparator = forward ? ">" : "<";
        String order = forward ? "ASC" : "DESC";
        String sql = "SELECT message_id,seq,sender_id,client_msg_id,type,content,created_at FROM "
                + tables.messages() + " WHERE conversation_id=? AND seq " + comparator
                + " ? AND seq>=? AND seq<=? ORDER BY seq " + order + " LIMIT ?";
        List<MessageView> rows = jdbc.query(sql, (rs, n) -> new MessageView(
                        UuidCodec.fromBytes(rs.getBytes("message_id")), rs.getLong("seq"),
                        UuidCodec.fromBytes(rs.getBytes("sender_id")),
                        UuidCodec.fromBytes(rs.getBytes("client_msg_id")), typeName(rs.getInt("type")),
                        json(rs.getString("content")), rs.getTimestamp("created_at").toInstant(), List.of()),
                bytes(conversationId), cursor, visibility.visibleFromSeq(),
                visibility.highWatermark(), limit + 1);

        boolean hasMore = rows.size() > limit;
        if (hasMore) rows = new ArrayList<>(rows.subList(0, limit));
        if (!forward) Collections.reverse(rows);
        rows = attach(tables.attachments(), conversationId, rows);
        Long nextBefore = rows.isEmpty() ? null : rows.getFirst().seq();
        Long nextAfter = rows.isEmpty() ? null : rows.getLast().seq();
        return new MessagePage(rows, forward ? "AFTER" : "BEFORE", hasMore,
                nextBefore, nextAfter, visibility.minAvailableSeq(),
                visibility.visibleFromSeq(), visibility.highWatermark());
    }

    MessageView get(UUID userId, UUID conversationId, long seq) {
        if (seq < 1) throw error("INVALID_SEQUENCE", "Message sequence must be positive", 400);
        Tables tables = tables(conversationId);
        Visibility visibility = visibility(tables, conversationId, userId);
        if (seq < visibility.visibleFromSeq()) {
            throw error("MESSAGE_NOT_VISIBLE", "Message is outside the member's visible history", 403);
        }
        List<MessageView> values = jdbc.query(
                "SELECT message_id,seq,sender_id,client_msg_id,type,content,created_at FROM "
                        + tables.messages() + " WHERE conversation_id=? AND seq=?",
                (rs, n) -> new MessageView(UuidCodec.fromBytes(rs.getBytes("message_id")),
                        rs.getLong("seq"), UuidCodec.fromBytes(rs.getBytes("sender_id")),
                        UuidCodec.fromBytes(rs.getBytes("client_msg_id")), typeName(rs.getInt("type")),
                        json(rs.getString("content")), rs.getTimestamp("created_at").toInstant(), List.of()),
                bytes(conversationId), seq);
        if (values.isEmpty()) throw error("MESSAGE_NOT_FOUND", "Message does not exist", 404);
        return attach(tables.attachments(), conversationId, values).getFirst();
    }

    private Visibility visibility(Tables tables, UUID conversationId, UUID userId) {
        List<Visibility> values = jdbc.query("SELECT c.last_seq,c.min_available_seq,m.joined_seq "
                        + "FROM " + tables.conversations() + " c JOIN " + tables.members()
                        + " m ON m.conversation_id=c.id WHERE c.id=? AND m.user_id=? "
                        + "AND c.status=1 AND m.status=1",
                (rs, n) -> {
                    long minimum = rs.getLong("min_available_seq");
                    long joined = rs.getLong("joined_seq");
                    return new Visibility(rs.getLong("last_seq"), minimum, Math.max(minimum, joined + 1));
                }, bytes(conversationId), bytes(userId));
        if (values.isEmpty()) {
            throw error("NOT_A_MEMBER", "User is not an active conversation member", 403);
        }
        return values.getFirst();
    }

    private List<MessageView> attach(String table, UUID conversationId, List<MessageView> messages) {
        if (messages.isEmpty()) return messages;
        String marks = String.join(",", messages.stream().map(value -> "?").toList());
        List<Object> parameters = new ArrayList<>();
        parameters.add(bytes(conversationId));
        messages.forEach(message -> parameters.add(message.seq()));
        Map<Long, List<AttachmentView>> bySequence = new HashMap<>();
        jdbc.query("SELECT id,bound_seq,file_name,mime_type,size_bytes,sha256 FROM " + table
                        + " WHERE conversation_id=? AND status=2 AND bound_seq IN (" + marks + ")",
                rs -> {
                    long sequence = rs.getLong("bound_seq");
                    UUID id = UuidCodec.fromBytes(rs.getBytes("id"));
                    byte[] digest = rs.getBytes("sha256");
                    AttachmentView value = new AttachmentView(id, rs.getString("file_name"),
                            rs.getString("mime_type"), rs.getLong("size_bytes"),
                            digest == null ? null : java.util.HexFormat.of().formatHex(digest),
                            "/api/v1/conversations/" + conversationId + "/attachments/" + id + "/content");
                    bySequence.computeIfAbsent(sequence, ignored -> new ArrayList<>()).add(value);
                }, parameters.toArray());
        return messages.stream().map(message -> new MessageView(message.id(), message.seq(),
                message.senderId(), message.clientMsgId(), message.type(), message.content(),
                message.createdAt(), bySequence.getOrDefault(message.seq(), List.of()))).toList();
    }

    private Tables tables(UUID conversationId) {
        ResolvedRoute route = routes.resolve(conversationId);
        if (!route.shardId().equals(localShard)) {
            throw error("ROUTE_MISMATCH", "Conversation belongs to " + route.shardId(), 503);
        }
        MessageTableNames names = new MessageTableNames(route.tableGroup(), 0);
        return new Tables(names.conversations(), names.members(), names.messages(), names.attachments());
    }

    private JsonNode json(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored message content is invalid JSON", exception);
        }
    }

    private String typeName(int type) {
        return switch (type) { case 1 -> "TEXT"; case 2 -> "IMAGE"; case 3 -> "FILE"; default -> "UNKNOWN"; };
    }

    private byte[] bytes(UUID value) { return UuidCodec.toBytes(value); }
    private ApiBusinessException error(String code, String message, int status) {
        return new ApiBusinessException(code, message, status);
    }
    private record Tables(String conversations, String members, String messages, String attachments) {}
    private record Visibility(long highWatermark, long minAvailableSeq, long visibleFromSeq) {}
}
