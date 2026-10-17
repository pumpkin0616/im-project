package com.example.im.routing;

import com.example.im.config.ImProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class RoutingHealthIndicator implements HealthIndicator {
    private final JdbcTemplate jdbc;
    private final ImProperties properties;
    private final RouteCacheControl cache;

    RoutingHealthIndicator(JdbcTemplate jdbc, ImProperties properties, RouteCacheControl cache) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.cache = cache;
    }

    @Override
    public Health health() {
        try {
            Integer routes = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.slot_routes", Integer.class);
            Integer activeMigrations = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.migration_jobs "
                    + "WHERE state NOT IN ('COMPLETED','FAILED')", Integer.class);
            Health.Builder builder = routes != null && routes == properties.routing().slotCount()
                    ? Health.up() : Health.down().withDetail("expectedSlotRoutes", properties.routing().slotCount());
            return builder.withDetail("slotRoutes", routes == null ? 0 : routes)
                    .withDetail("activeMigrations", activeMigrations == null ? 0 : activeMigrations)
                    .withDetail("cacheEntries", cache.cachedRouteCount())
                    .withDetail("routeVersion", cache.observedRouteVersion()).build();
        } catch (RuntimeException exception) {
            return Health.down(exception).build();
        }
    }
}
