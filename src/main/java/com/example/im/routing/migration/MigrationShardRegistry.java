package com.example.im.routing.migration;

import com.example.im.config.ImProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Component
final class MigrationShardRegistry {
    private final String localShard;
    private final Map<String, JdbcTemplate> templates = new HashMap<>();
    private final Map<String, HikariDataSource> managed = new HashMap<>();

    MigrationShardRegistry(DataSource primary, ImProperties properties) {
        this.localShard = properties.routing().localShardId();
        templates.put(localShard, new JdbcTemplate(primary));
        properties.routing().migrationShards().forEach((shardId, connection) -> {
            if (shardId.equals(localShard)) return;
            HikariConfig config = new HikariConfig();
            config.setPoolName("migration-" + shardId);
            config.setJdbcUrl(connection.url());
            config.setUsername(connection.username());
            config.setPassword(connection.password());
            config.setMaximumPoolSize(connection.maximumPoolSize());
            config.setMinimumIdle(0);
            config.setConnectionTimeout(3_000);
            config.setReadOnly(false);
            HikariDataSource dataSource = new HikariDataSource(config);
            managed.put(shardId, dataSource);
            templates.put(shardId, new JdbcTemplate(dataSource));
        });
    }

    JdbcTemplate require(String shardId) {
        JdbcTemplate template = templates.get(shardId);
        if (template == null) throw new IllegalArgumentException("No migration connection is configured for " + shardId);
        return template;
    }

    String localShard() {
        return localShard;
    }

    @PreDestroy
    void close() {
        managed.values().forEach(HikariDataSource::close);
    }
}
