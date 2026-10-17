package com.example.im.outbox;

import com.example.im.routing.UuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class JdbcOutboxRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    JdbcOutboxRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    List<OutboxEvent> claim(int tableGroup, int limit, UUID leaseOwner, Duration lease) {
        String table = tableName(tableGroup);
        String conversations = String.format("conversations_%02d", tableGroup);
        return transactions.execute(status -> {
            String select = "SELECT o.id,o.event_id,o.event_type,o.schema_version,o.slot_id,"
                    + "o.conversation_id,o.message_day,o.message_seq,o.payload,o.attempts FROM " + table + " o "
                    + "JOIN " + conversations + " c ON c.id=o.conversation_id "
                    + "WHERE c.write_state<>3 "
                    + "AND NOT EXISTS (SELECT 1 FROM im_control.conversation_routes cr "
                    + "WHERE cr.conversation_id=o.conversation_id AND cr.state=2) "
                    + "AND NOT EXISTS (SELECT 1 FROM im_control.slot_routes sr WHERE sr.slot_id=o.slot_id AND sr.state=2) "
                    + "AND ((o.status=0 AND o.available_at<=UTC_TIMESTAMP(3)) "
                    + "OR (o.status=1 AND o.lease_until<UTC_TIMESTAMP(3))) "
                    + "ORDER BY o.id LIMIT ? FOR UPDATE SKIP LOCKED";
            List<OutboxEvent> events = jdbc.query(select,
                    (rs, rowNumber) -> mapEvent(tableGroup, rs), limit);
            if (events.isEmpty()) {
                return List.of();
            }

            String placeholders = String.join(",", events.stream().map(ignored -> "?").toList());
            List<Object> parameters = new ArrayList<>();
            parameters.add(UuidCodec.toBytes(leaseOwner));
            parameters.add(lease.toMillis());
            events.forEach(event -> parameters.add(event.id()));
            String update = "UPDATE " + table
                    + " SET status = 1, attempts = attempts + 1, lease_owner = ?, "
                    + "lease_until = TIMESTAMPADD(MICROSECOND, ? * 1000, UTC_TIMESTAMP(3)) "
                    + "WHERE id IN (" + placeholders + ")";
            int affected = jdbc.update(update, parameters.toArray());
            if (affected != events.size()) {
                throw new IllegalStateException("Expected to lease " + events.size() + " events, got " + affected);
            }
            return events.stream().map(event -> new OutboxEvent(
                    event.tableGroup(), event.id(), event.eventId(), event.eventType(),
                    event.schemaVersion(), event.slotId(), event.conversationId(),
                    event.messageDay(), event.messageSequence(), event.payload(), event.attempts() + 1
            )).toList();
        });
    }

    boolean markPublished(OutboxEvent event, UUID leaseOwner) {
        String sql = "UPDATE " + tableName(event.tableGroup())
                + " SET status = 2, published_at = UTC_TIMESTAMP(3), lease_owner = NULL, lease_until = NULL "
                + "WHERE id = ? AND status = 1 AND lease_owner = ?";
        return jdbc.update(sql, event.id(), UuidCodec.toBytes(leaseOwner)) == 1;
    }

    boolean releaseAfterFailure(OutboxEvent event, UUID leaseOwner, int maxAttempts) {
        int delaySeconds = Math.min(60, 1 << Math.min(6, Math.max(0, event.attempts() - 1)));
        int status = event.attempts() >= maxAttempts ? 3 : 0;
        String sql = "UPDATE " + tableName(event.tableGroup())
                + " SET status = ?, available_at = TIMESTAMPADD(SECOND, ?, UTC_TIMESTAMP(3)), "
                + "lease_owner = NULL, lease_until = NULL "
                + "WHERE id = ? AND status = 1 AND lease_owner = ?";
        return jdbc.update(sql, status, delaySeconds, event.id(), UuidCodec.toBytes(leaseOwner)) == 1;
    }

    private OutboxEvent mapEvent(int tableGroup, ResultSet rs) throws SQLException {
        Date messageDay = rs.getDate("message_day");
        long rawSequence = rs.getLong("message_seq");
        Long messageSequence = rs.wasNull() ? null : rawSequence;
        return new OutboxEvent(
                tableGroup,
                rs.getLong("id"),
                UuidCodec.fromBytes(rs.getBytes("event_id")),
                rs.getString("event_type"),
                rs.getInt("schema_version"),
                rs.getInt("slot_id"),
                UuidCodec.fromBytes(rs.getBytes("conversation_id")),
                messageDay == null ? null : messageDay.toLocalDate(),
                messageSequence,
                parseJson(rs.getString("payload")),
                rs.getInt("attempts")
        );
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("Invalid JSON in outbox", exception);
        }
    }

    private static String tableName(int tableGroup) {
        if (tableGroup < 0 || tableGroup > 7) {
            throw new IllegalArgumentException("tableGroup must be between 0 and 7");
        }
        return String.format("outbox_%02d", tableGroup);
    }
}
