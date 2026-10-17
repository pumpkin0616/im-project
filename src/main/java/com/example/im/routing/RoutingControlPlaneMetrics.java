package com.example.im.routing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
final class RoutingControlPlaneMetrics {
    private final JdbcTemplate jdbc;
    private final AtomicLong slotRoutes = new AtomicLong();
    private final AtomicLong activeMigrations = new AtomicLong();
    private final AtomicLong oldestActiveMigrationSeconds = new AtomicLong();
    private final Counter refreshFailures;

    RoutingControlPlaneMetrics(JdbcTemplate jdbc, MeterRegistry registry) {
        this.jdbc = jdbc;
        Gauge.builder("im.routing.slot.routes", slotRoutes, AtomicLong::get).register(registry);
        Gauge.builder("im.routing.active.migrations", activeMigrations, AtomicLong::get).register(registry);
        Gauge.builder("im.routing.oldest.active.migration.seconds", oldestActiveMigrationSeconds, AtomicLong::get)
                .register(registry);
        this.refreshFailures = registry.counter("im.routing.metrics.refresh.failures");
        refresh();
    }

    @Scheduled(fixedDelayString = "${im.routing.metrics-refresh-interval:5s}")
    void refresh() {
        try {
            Long routes = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.slot_routes", Long.class);
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.migration_jobs "
                    + "WHERE state NOT IN ('COMPLETED','FAILED')", Long.class);
            Long oldest = jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,created_at,UTC_TIMESTAMP())),0) "
                    + "FROM im_control.migration_jobs WHERE state NOT IN ('COMPLETED','FAILED')", Long.class);
            slotRoutes.set(routes == null ? 0 : routes);
            activeMigrations.set(active == null ? 0 : active);
            oldestActiveMigrationSeconds.set(oldest == null ? 0 : oldest);
        } catch (RuntimeException exception) {
            refreshFailures.increment();
        }
    }
}
