package com.example.im.gateway;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.UuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Service
public final class GatewayTicketService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final UuidV7Generator ids;
    private final ImProperties.Gateway config;
    private final SecureRandom random = new SecureRandom();

    public GatewayTicketService(JdbcTemplate jdbc, TransactionTemplate transactions,
                                UuidV7Generator ids, ImProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.ids = ids;
        this.config = properties.gateway();
    }

    TicketResponse issue(UUID userId, UUID sessionId, UUID deviceId) {
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.auth_sessions s "
                        + "JOIN im_control.users u ON u.id=s.user_id "
                        + "WHERE s.id=? AND s.user_id=? AND s.device_id=? AND s.revoked_at IS NULL "
                        + "AND s.expires_at>UTC_TIMESTAMP(3) AND u.status=1",
                Integer.class, bytes(sessionId), bytes(userId), bytes(deviceId));
        if (active == null || active == 0) {
            throw new ApiBusinessException("SESSION_INACTIVE", "Authentication session is inactive", 401);
        }
        String ticket = randomToken();
        UUID ticketId = ids.next();
        Instant now = Instant.now();
        Instant expiresAt = now.plus(config.ticketTtl());
        jdbc.update("INSERT INTO im_control.websocket_tickets(ticket_hash,id,user_id,session_id,device_id,expires_at,created_at) "
                        + "VALUES(?,?,?,?,?,?,?)", hash(ticket), bytes(ticketId), bytes(userId),
                bytes(sessionId), bytes(deviceId), Timestamp.from(expiresAt), Timestamp.from(now));
        return new TicketResponse(ticketId, ticket, config.publicUrl() + "?ticket=" + ticket, expiresAt);
    }

    public Identity consume(String rawTicket) {
        if (rawTicket == null || rawTicket.length() < 32 || rawTicket.length() > 128) return null;
        return transactions.execute(status -> {
            List<Identity> identities = jdbc.query("SELECT t.user_id,t.session_id,t.device_id FROM im_control.websocket_tickets t "
                            + "JOIN im_control.auth_sessions s ON s.id=t.session_id AND s.user_id=t.user_id AND s.device_id=t.device_id "
                            + "JOIN im_control.users u ON u.id=t.user_id "
                            + "WHERE t.ticket_hash=? AND t.used_at IS NULL AND t.expires_at>UTC_TIMESTAMP(3) "
                            + "AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(3) AND u.status=1 FOR UPDATE",
                    (rs, n) -> new Identity(UuidCodec.fromBytes(rs.getBytes(1)),
                            UuidCodec.fromBytes(rs.getBytes(2)), UuidCodec.fromBytes(rs.getBytes(3))),
                    hash(rawTicket));
            if (identities.isEmpty()) return null;
            int changed = jdbc.update("UPDATE im_control.websocket_tickets SET used_at=UTC_TIMESTAMP(3) "
                    + "WHERE ticket_hash=? AND used_at IS NULL", hash(rawTicket));
            return changed == 1 ? identities.getFirst() : null;
        });
    }

    @Scheduled(fixedDelayString = "${IM_GATEWAY_TICKET_CLEANUP_MS:60000}")
    void cleanup() {
        jdbc.update("DELETE FROM im_control.websocket_tickets WHERE expires_at<UTC_TIMESTAMP(3)-INTERVAL 5 MINUTE");
    }

    private String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private byte[] hash(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] bytes(UUID value) { return UuidCodec.toBytes(value); }

    public record Identity(UUID userId, UUID sessionId, UUID deviceId) {}
    record TicketResponse(UUID ticketId, String ticket, String websocketUrl, Instant expiresAt) {}
}
