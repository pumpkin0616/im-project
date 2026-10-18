package com.example.im.archive;

import com.example.im.config.ImProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;

@Component
final class LifecycleHealthIndicator implements HealthIndicator {
    private final JdbcTemplate jdbc;
    private final Path archiveRoot;

    LifecycleHealthIndicator(JdbcTemplate jdbc, ImProperties properties) {
        this.jdbc = jdbc;
        this.archiveRoot = properties.archive().root().toAbsolutePath().normalize();
    }

    @Override
    public Health health() {
        try {
            Files.createDirectories(archiveRoot);
            Long failedRuns = jdbc.queryForObject("SELECT COUNT(*) FROM lifecycle_runs WHERE state='FAILED'", Long.class);
            boolean writable = Files.isWritable(archiveRoot);
            Health.Builder health = writable ? Health.up() : Health.down();
            return health.withDetail("archiveRoot", archiveRoot.toString())
                    .withDetail("archiveRootWritable", writable)
                    .withDetail("failedLifecycleRuns", failedRuns == null ? 0 : failedRuns).build();
        } catch (RuntimeException | java.io.IOException exception) {
            return Health.down(exception).build();
        }
    }
}
