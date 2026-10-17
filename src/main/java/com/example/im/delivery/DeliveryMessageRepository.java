package com.example.im.delivery;

import com.example.im.config.ImProperties;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;

@Repository
public class DeliveryMessageRepository {
    private final JdbcTemplate jdbc;
    private final ConversationRouteResolver routes;
    private final ObjectMapper objectMapper;
    private final String localShard;

    public DeliveryMessageRepository(JdbcTemplate jdbc, ConversationRouteResolver routes,
                              ObjectMapper objectMapper, ImProperties properties) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.objectMapper = objectMapper;
        this.localShard = properties.routing().localShardId();
    }

    DeliveryMessage load(UUID conversationId, long sequence) {
        ResolvedRoute route = routes.resolve(conversationId);
        if (!localShard.equals(route.shardId())) throw new IllegalStateException("Message event routed to wrong shard");
        MessageTableNames tables = new MessageTableNames(route.tableGroup(), 0);
        List<DeliveryMessage> values = jdbc.query("SELECT message_id,sender_id,client_msg_id,type,content,created_at "
                        + "FROM " + tables.messages() + " WHERE conversation_id=? AND seq=?",
                (rs, n) -> new DeliveryMessage(conversationId,
                        UuidCodec.fromBytes(rs.getBytes("message_id")), sequence,
                        UuidCodec.fromBytes(rs.getBytes("sender_id")),
                        UuidCodec.fromBytes(rs.getBytes("client_msg_id")), typeName(rs.getInt("type")),
                        json(rs.getString("content")), rs.getTimestamp("created_at").toInstant(),
                        List.of(), List.of()), UuidCodec.toBytes(conversationId), sequence);
        if (values.isEmpty()) throw new IllegalStateException("Message event references a missing message");
        DeliveryMessage value = values.getFirst();
        List<UUID> recipients = jdbc.query("SELECT user_id FROM " + tables.members()
                        + " WHERE conversation_id=? AND status=1 AND joined_seq < ?",
                (rs, n) -> UuidCodec.fromBytes(rs.getBytes(1)), UuidCodec.toBytes(conversationId), sequence);
        List<Map<String, Object>> attachments = new ArrayList<>();
        jdbc.query("SELECT id,file_name,mime_type,size_bytes,sha256 FROM " + tables.attachments()
                        + " WHERE conversation_id=? AND status=2 AND bound_seq=?", rs -> {
                    Map<String, Object> attachment = new HashMap<>();
                    UUID id = UuidCodec.fromBytes(rs.getBytes("id"));
                    attachment.put("id", id.toString());
                    attachment.put("fileName", rs.getString("file_name"));
                    attachment.put("mimeType", rs.getString("mime_type"));
                    attachment.put("sizeBytes", rs.getLong("size_bytes"));
                    byte[] digest = rs.getBytes("sha256");
                    if (digest != null) attachment.put("sha256", java.util.HexFormat.of().formatHex(digest));
                    attachment.put("downloadUrl", "/api/v1/conversations/" + conversationId + "/attachments/" + id + "/content");
                    attachments.add(attachment);
                }, UuidCodec.toBytes(conversationId), sequence);
        return new DeliveryMessage(value.conversationId(), value.messageId(), value.sequence(), value.senderId(),
                value.clientMessageId(), value.type(), value.content(), value.createdAt(), attachments, recipients);
    }

    RecallDelivery loadRecall(UUID conversationId, long sequence) {
        ResolvedRoute route = routes.resolve(conversationId);
        if (!localShard.equals(route.shardId())) throw new IllegalStateException("Recall event routed to wrong shard");
        MessageTableNames tables = new MessageTableNames(route.tableGroup(), 0);
        List<RecallDelivery> values = jdbc.query("SELECT message_id,recalled_by,recalled_at,status FROM "
                        + tables.messages() + " WHERE conversation_id=? AND seq=?",
                (rs, n) -> new RecallDelivery(conversationId,
                        UuidCodec.fromBytes(rs.getBytes("message_id")), sequence,
                        UuidCodec.fromBytes(rs.getBytes("recalled_by")),
                        rs.getTimestamp("recalled_at").toInstant(), List.of()),
                UuidCodec.toBytes(conversationId), sequence);
        if (values.isEmpty()) throw new IllegalStateException("Recall event references a missing message");
        List<UUID> recipients = jdbc.query("SELECT user_id FROM " + tables.members()
                        + " WHERE conversation_id=? AND status=1 AND joined_seq < ?",
                (rs, n) -> UuidCodec.fromBytes(rs.getBytes(1)), UuidCodec.toBytes(conversationId), sequence);
        RecallDelivery value = values.getFirst();
        return new RecallDelivery(value.conversationId(), value.messageId(), value.sequence(),
                value.recalledBy(), value.recalledAt(), recipients);
    }

    record RecallDelivery(UUID conversationId, UUID messageId, long sequence, UUID recalledBy,
                          Instant recalledAt, List<UUID> recipients) {
        Map<String, Object> payload() {
            return Map.of("conversationId", conversationId.toString(), "messageId", messageId.toString(),
                    "seq", Long.toUnsignedString(sequence), "recalledBy", recalledBy.toString(),
                    "recalledAt", recalledAt.toString());
        }
    }

    private JsonNode json(String value) {
        try { return objectMapper.readTree(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Stored message content is invalid", exception); }
    }
    private String typeName(int type) { return switch (type) { case 1 -> "TEXT"; case 2 -> "IMAGE"; case 3 -> "FILE"; default -> "UNKNOWN"; }; }
}
