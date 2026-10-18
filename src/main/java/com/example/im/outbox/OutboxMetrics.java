package com.example.im.outbox;

import com.example.im.config.ImProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
final class OutboxMetrics {
    private final JdbcTemplate jdbc;
    private final int tableGroups;
    private final AtomicLong backlog = new AtomicLong();
    private final AtomicLong manual = new AtomicLong();
    private final AtomicLong oldestUnpublishedSeconds = new AtomicLong();
    private final Counter refreshFailures;

    OutboxMetrics(JdbcTemplate jdbc, ImProperties properties, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.tableGroups = properties.routing().tableGroupCount();
        Gauge.builder("im.outbox.backlog", backlog, AtomicLong::get).register(meters);
        Gauge.builder("im.outbox.manual", manual, AtomicLong::get).register(meters);
        Gauge.builder("im.outbox.oldest.unpublished.seconds", oldestUnpublishedSeconds, AtomicLong::get)
                .register(meters);
        this.refreshFailures = meters.counter("im.outbox.metrics.refresh.failures");
    }

    @Scheduled(fixedDelayString = "${IM_OUTBOX_METRICS_INTERVAL_MS:5000}")
    void refresh() {
        try {
            long pendingCount = 0;
            long manualCount = 0;
            long oldestSeconds = 0;
            for (int group = 0; group < tableGroups; group++) {
                String table = String.format("outbox_%02d", group);
                String conversations = String.format("conversations_%02d", group);
                String eligible = " FROM " + table + " o JOIN " + conversations
                        + " c ON c.id=o.conversation_id WHERE c.write_state<>3 "
                        + "AND NOT EXISTS (SELECT 1 FROM im_control.conversation_routes cr "
                        + "WHERE cr.conversation_id=o.conversation_id AND cr.state=2) "
                        + "AND NOT EXISTS (SELECT 1 FROM im_control.slot_routes sr "
                        + "WHERE sr.slot_id=o.slot_id AND sr.state=2) ";
                Number pending = jdbc.queryForObject("SELECT COUNT(*)" + eligible + "AND o.status IN (0,1)", Number.class);
                Number manualValue = jdbc.queryForObject("SELECT COUNT(*)" + eligible + "AND o.status=3", Number.class);
                Number oldest = jdbc.queryForObject("SELECT COALESCE(TIMESTAMPDIFF(SECOND,MIN(o.created_at),UTC_TIMESTAMP(3)),0)"
                        + eligible + "AND o.status IN (0,1)", Number.class);
                pendingCount += pending == null ? 0 : pending.longValue();
                manualCount += manualValue == null ? 0 : manualValue.longValue();
                oldestSeconds = Math.max(oldestSeconds, oldest == null ? 0 : oldest.longValue());
            }
            backlog.set(pendingCount);
            manual.set(manualCount);
            oldestUnpublishedSeconds.set(Math.max(0, oldestSeconds));
        } catch (RuntimeException exception) {
            refreshFailures.increment();
        }
    }
}
