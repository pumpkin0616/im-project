package com.example.im.routing;

import com.example.im.message.domain.MessageRejectedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class RouteWriteGuard {
    public void lock(JdbcTemplate jdbc, ResolvedRoute route, UUID conversationId) {
        String sql;
        Object key;
        if (route.scope() == RouteScope.CONVERSATION) {
            sql = "SELECT route_epoch,writable FROM conversation_fences WHERE conversation_id=? FOR SHARE";
            key = UuidCodec.toBytes(conversationId);
        } else {
            sql = "SELECT route_epoch,writable FROM slot_fences WHERE slot_id=? FOR SHARE";
            key = route.slotId();
        }
        List<Map<String, Object>> rows = jdbc.queryForList(sql, key);
        if (rows.isEmpty()) {
            throw new MessageRejectedException("ROUTE_MISMATCH", "The route fence is missing", true);
        }
        Map<String, Object> row = rows.getFirst();
        long epoch = ((Number) row.get("route_epoch")).longValue();
        Object rawWritable = row.get("writable");
        boolean writable = rawWritable instanceof Boolean value
                ? value : ((Number) rawWritable).intValue() != 0;
        if (!writable || epoch != route.routeEpoch()) {
            throw new MessageRejectedException("ROUTE_STALE", "The route is not writable", true);
        }
    }
}
