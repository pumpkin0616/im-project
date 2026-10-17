package com.example.im.read;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.delivery.RealtimeDeliveryRouter;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.UuidCodec;
import com.example.im.routing.RouteWriteGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public final class ReadReceiptService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ConversationRouteResolver routes;
    private final RealtimeDeliveryRouter delivery;
    private final String localShard;
    private final RouteWriteGuard routeWriteGuard;

    public ReadReceiptService(JdbcTemplate jdbc, TransactionTemplate transactions,
                              ConversationRouteResolver routes, RealtimeDeliveryRouter delivery,
                              ImProperties properties, RouteWriteGuard routeWriteGuard) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.routes = routes;
        this.delivery = delivery;
        this.localShard = properties.routing().localShardId();
        this.routeWriteGuard = routeWriteGuard;
    }

    public ReadUpdate advance(UUID userId, UUID conversationId, long requestedSequence) {
        if (requestedSequence < 0) throw error("INVALID_SEQUENCE", "Read sequence must not be negative", 400);
        State state = transactions.execute(status -> advanceInTransaction(userId, conversationId, requestedSequence));
        if (state == null) throw new IllegalStateException("Read transaction returned no result");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId.toString());
        payload.put("userId", userId.toString());
        payload.put("readSeq", Long.toUnsignedString(state.readSequence()));
        payload.put("highWatermark", Long.toUnsignedString(state.highWatermark()));
        delivery.dispatch(state.notifyUsers(), "READ_UPDATE", payload);
        return new ReadUpdate(conversationId, userId, state.readSequence(), state.highWatermark());
    }

    private State advanceInTransaction(UUID userId, UUID conversationId, long requestedSequence) {
        ResolvedRoute route = routes.resolve(conversationId);
        if (!localShard.equals(route.shardId())) throw error("ROUTE_MISMATCH", "Conversation belongs to " + route.shardId(), 503);
        routeWriteGuard.lock(jdbc, route, conversationId);
        MessageTableNames names = new MessageTableNames(route.tableGroup(), 0);
        List<ConversationState> conversations = jdbc.query("SELECT type,last_seq FROM " + names.conversations()
                        + " WHERE id=? AND status=1", (rs, n) -> new ConversationState(rs.getInt(1), rs.getLong(2)),
                UuidCodec.toBytes(conversationId));
        if (conversations.isEmpty()) throw error("CONVERSATION_NOT_FOUND", "Conversation does not exist", 404);
        ConversationState conversation = conversations.getFirst();
        long effective = Math.min(requestedSequence, conversation.highWatermark());
        int affected = jdbc.update("UPDATE " + names.members()
                        + " SET last_read_seq=GREATEST(last_read_seq,?) WHERE conversation_id=? AND user_id=? AND status=1",
                effective, UuidCodec.toBytes(conversationId), UuidCodec.toBytes(userId));
        if (affected != 1) throw error("NOT_A_MEMBER", "User is not an active conversation member", 403);
        Long current = jdbc.queryForObject("SELECT last_read_seq FROM " + names.members()
                        + " WHERE conversation_id=? AND user_id=?", Long.class,
                UuidCodec.toBytes(conversationId), UuidCodec.toBytes(userId));
        List<UUID> notifyUsers = conversation.type() == 1
                ? jdbc.query("SELECT user_id FROM " + names.members() + " WHERE conversation_id=? AND status=1",
                    (rs, n) -> UuidCodec.fromBytes(rs.getBytes(1)), UuidCodec.toBytes(conversationId))
                : List.of(userId);
        return new State(current == null ? effective : current, conversation.highWatermark(), notifyUsers);
    }

    private ApiBusinessException error(String code, String message, int status) {
        return new ApiBusinessException(code, message, status);
    }

    public record ReadUpdate(UUID conversationId, UUID userId, long readSeq, long highWatermark) {}
    private record ConversationState(int type, long highWatermark) {}
    private record State(long readSequence, long highWatermark, List<UUID> notifyUsers) {}
}
