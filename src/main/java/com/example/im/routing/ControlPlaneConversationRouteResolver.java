package com.example.im.routing;

import com.example.im.config.ImProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(prefix = "im.routing", name = "mode", havingValue = "control-plane", matchIfMissing = true)
public final class ControlPlaneConversationRouteResolver implements ConversationRouteResolver, RouteCacheControl {
    private final JdbcTemplate jdbc;
    private final SlotCalculator slots;
    private final Duration ttl;
    private final int maximumEntries;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, CachedRoute> cache = new ConcurrentHashMap<>();
    private final Counter hits;
    private final Counter misses;
    private final Counter invalidations;
    private volatile long observedVersion;

    @Autowired
    public ControlPlaneConversationRouteResolver(JdbcTemplate jdbc, SlotCalculator slots,
                                                 ImProperties properties, MeterRegistry metrics) {
        this(jdbc, slots, properties.routing().cacheTtl(), properties.routing().cacheMaxEntries(),
                Clock.systemUTC(), metrics);
    }

    ControlPlaneConversationRouteResolver(JdbcTemplate jdbc, SlotCalculator slots, Duration ttl,
                                          int maximumEntries, Clock clock, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.slots = slots;
        this.ttl = ttl;
        this.maximumEntries = maximumEntries;
        this.clock = clock;
        this.hits = metrics.counter("im.routing.cache", "result", "hit");
        this.misses = metrics.counter("im.routing.cache", "result", "miss");
        this.invalidations = metrics.counter("im.routing.cache.invalidations");
        this.observedVersion = readVersion();
        metrics.gauge("im.routing.cache.entries", cache, ConcurrentHashMap::size);
    }

    @Override
    public ResolvedRoute resolve(UUID conversationId) {
        long now = clock.millis();
        CachedRoute cached = cache.get(conversationId);
        if (cached != null && cached.expiresAtMillis() > now) {
            hits.increment();
            return requireUsable(cached.route());
        }
        misses.increment();
        ResolvedRoute loaded = load(conversationId);
        if (cache.size() >= maximumEntries) invalidateAll();
        cache.put(conversationId, new CachedRoute(loaded, now + ttl.toMillis()));
        return requireUsable(loaded);
    }

    private ResolvedRoute load(UUID conversationId) {
        List<ResolvedRoute> overrides = jdbc.query(
                "SELECT shard_id,table_group,route_epoch,state FROM im_control.conversation_routes WHERE conversation_id=?",
                (rs, row) -> new ResolvedRoute(slots.slot(conversationId), rs.getString("shard_id"),
                        rs.getInt("table_group"), rs.getLong("route_epoch"),
                        RouteState.fromDatabase(rs.getInt("state")), RouteScope.CONVERSATION),
                UuidCodec.toBytes(conversationId));
        if (!overrides.isEmpty()) return overrides.getFirst();

        int slot = slots.slot(conversationId);
        List<ResolvedRoute> routes = jdbc.query(
                "SELECT shard_id,table_group,route_epoch,state FROM im_control.slot_routes WHERE slot_id=?",
                (rs, row) -> new ResolvedRoute(slot, rs.getString("shard_id"), rs.getInt("table_group"),
                        rs.getLong("route_epoch"), RouteState.fromDatabase(rs.getInt("state"))), slot);
        if (routes.isEmpty()) {
            throw new RouteResolutionException("ROUTE_NOT_PROVISIONED", "No route exists for slot " + slot);
        }
        return routes.getFirst();
    }

    private ResolvedRoute requireUsable(ResolvedRoute route) {
        if (route.state() == RouteState.DISABLED) {
            throw new RouteResolutionException("ROUTE_DISABLED", "The conversation route is disabled");
        }
        return route;
    }

    @Scheduled(fixedDelayString = "${im.routing.revision-poll-interval:1s}")
    public void refreshRevision() {
        long current = readVersion();
        if (current != observedVersion) {
            observedVersion = current;
            invalidateAll();
        }
    }

    private long readVersion() {
        Long value = jdbc.queryForObject("SELECT route_version FROM im_control.routing_metadata WHERE id=1", Long.class);
        if (value == null) throw new RouteResolutionException("ROUTE_METADATA_MISSING", "Routing metadata is missing");
        return value;
    }

    @Override public void invalidate(UUID conversationId) { cache.remove(conversationId); invalidations.increment(); }
    @Override public void invalidateAll() { cache.clear(); invalidations.increment(); }
    @Override public long cachedRouteCount() { return cache.size(); }
    @Override public long observedRouteVersion() { return observedVersion; }

    private record CachedRoute(ResolvedRoute route, long expiresAtMillis) {}
}
