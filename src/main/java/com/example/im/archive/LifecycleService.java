package com.example.im.archive;

import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.UuidCodec;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.example.im.archive.ArchiveDtos.*;

@Service
final class LifecycleService {
    private static final Logger log = LoggerFactory.getLogger(LifecycleService.class);
    private final JdbcTemplate jdbc;
    private final MessageArchiveService archive;
    private final ImProperties properties;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final Path attachmentRoot;
    private final Counter cleanupFailures;
    private final Counter cleanupCompleted;
    private final AtomicLong archiveBacklog = new AtomicLong();
    private final AtomicLong failedArchiveBatches = new AtomicLong();
    private final AtomicLong oldestActiveArchiveSeconds = new AtomicLong();

    LifecycleService(JdbcTemplate jdbc, MessageArchiveService archive, ImProperties properties,
                     UuidV7Generator ids, ObjectMapper json, MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.archive = archive;
        this.properties = properties;
        this.ids = ids;
        this.json = json;
        this.attachmentRoot = properties.storage().root().toAbsolutePath().normalize();
        this.cleanupFailures = metrics.counter("im.lifecycle.cleanup", "result", "failed");
        this.cleanupCompleted = metrics.counter("im.lifecycle.cleanup", "result", "completed");
        Gauge.builder("im.lifecycle.archive.backlog", archiveBacklog, AtomicLong::get).register(metrics);
        Gauge.builder("im.lifecycle.archive.failed.batches", failedArchiveBatches, AtomicLong::get).register(metrics);
        Gauge.builder("im.lifecycle.archive.oldest.active.seconds", oldestActiveArchiveSeconds, AtomicLong::get)
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${IM_ARCHIVE_POLL_INTERVAL_MS:60000}")
    void archiveNextEligibleDay() {
        if (!properties.archive().enabled()) return;
        LocalDate cutoff = Instant.now().minus(properties.archive().hotRetention()).atZone(ZoneOffset.UTC).toLocalDate();
        refreshBacklog(cutoff);
        for (int group = 0; group < 8; group++) {
            int tableGroup = group;
            MessageTableNames names = new MessageTableNames(tableGroup, 0);
            List<Scope> scopes = jdbc.query("SELECT m.slot_id,m.message_day FROM " + names.messages() + " m JOIN "
                            + names.conversations() + " c ON c.id=m.conversation_id AND c.write_state<>3"
                            + " WHERE m.message_day<? GROUP BY m.slot_id,m.message_day ORDER BY m.message_day LIMIT 1",
                    (rs, n) -> new Scope(tableGroup, rs.getInt(1), rs.getDate(2).toLocalDate()), Date.valueOf(cutoff));
            if (!scopes.isEmpty()) {
                Scope scope = scopes.getFirst();
                try { archive.archive(new ArchiveRequest(scope.tableGroup(), scope.slotId(), scope.day())); }
                catch (RuntimeException exception) {
                    log.error("Scheduled archive failed: group={}, slot={}, day={}", group, scope.slotId(), scope.day(), exception);
                }
                return;
            }
        }
    }

    @Scheduled(fixedDelayString = "${IM_LIFECYCLE_CLEANUP_INTERVAL_MS:3600000}")
    void scheduledCleanup() {
        if (!properties.archive().enabled()) return;
        try { cleanup(); }
        catch (RuntimeException exception) { cleanupFailures.increment(); log.error("Lifecycle cleanup failed", exception); }
    }

    @Scheduled(cron = "${IM_PARTITION_MAINTENANCE_CRON:0 15 1 * * *}", zone = "UTC")
    void scheduledPartitions() {
        if (!properties.archive().enabled()) return;
        ensurePartitions();
    }

    void ensurePartitions() {
        LocalDate through = LocalDate.now(ZoneOffset.UTC).plusDays(properties.archive().partitionAheadDays());
        jdbc.update("CALL ensure_message_partitions(?)", Date.valueOf(through));
    }

    CleanupView cleanup() {
        UUID runId = ids.next();
        Instant started = Instant.now();
        jdbc.update("INSERT INTO lifecycle_runs(id,run_type,state,details,started_at) "
                        + "VALUES(?,'CLEANUP','RUNNING',CAST('{}' AS JSON),?)",
                UuidCodec.toBytes(runId), Timestamp.from(started));
        Map<String, Long> deleted = new LinkedHashMap<>();
        try {
            long recovered = recoverStaleArchiveBatches();
            long outbox = 0, dedup = 0, attachments = 0;
            for (int group = 0; group < 8; group++) {
                MessageTableNames names = new MessageTableNames(group, 0);
                outbox += deleteBatches("DELETE FROM " + names.outbox()
                                + " WHERE status=2 AND published_at<? LIMIT ?",
                        Timestamp.from(Instant.now().minus(properties.archive().outboxRetention())));
                attachments += cleanupAttachments(names);
            }
            dedup += cleanupDedup();
            deleted.put("outbox", outbox);
            deleted.put("dedup", dedup);
            deleted.put("attachments", attachments);
            deleted.put("staleArchiveBatches", recovered);
            Instant completed = Instant.now();
            jdbc.update("UPDATE lifecycle_runs SET state='COMPLETED',details=CAST(? AS JSON),completed_at=? WHERE id=?",
                    encode(deleted), Timestamp.from(completed), UuidCodec.toBytes(runId));
            cleanupCompleted.increment();
            return new CleanupView(runId, Map.copyOf(deleted), completed);
        } catch (RuntimeException exception) {
            jdbc.update("UPDATE lifecycle_runs SET state='FAILED',last_error=?,completed_at=UTC_TIMESTAMP(3) WHERE id=?",
                    safeMessage(exception), UuidCodec.toBytes(runId));
            throw exception;
        }
    }

    Map<String, Object> status() {
        LocalDate cutoff = Instant.now().minus(properties.archive().hotRetention()).atZone(ZoneOffset.UTC).toLocalDate();
        refreshBacklog(cutoff);
        Long failed = jdbc.queryForObject("SELECT COUNT(*) FROM lifecycle_runs WHERE state='FAILED'", Long.class);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("enabled", properties.archive().enabled());
        value.put("archiveBacklogScopes", archiveBacklog.get());
        value.put("failedRuns", failed == null ? 0 : failed);
        value.put("failedArchiveBatches", failedArchiveBatches.get());
        value.put("hotRetentionDays", properties.archive().hotRetention().toDays());
        value.put("archiveRoot", properties.archive().root().toString());
        return value;
    }

    private long cleanupAttachments(MessageTableNames names) {
        Instant failedCutoff = Instant.now().minus(properties.archive().failedUploadRetention());
        Instant readyCutoff = Instant.now().minus(properties.archive().attachmentRetention());
        List<AttachmentObject> objects = jdbc.query("SELECT id,object_key FROM " + names.attachments()
                        + " WHERE (status IN (0,1,3,4) AND created_at<?) OR (status=2 AND created_at<?) LIMIT ?",
                (rs, n) -> new AttachmentObject(rs.getBytes(1), rs.getString(2)),
                Timestamp.from(failedCutoff), Timestamp.from(readyCutoff), properties.archive().batchSize());
        long deleted = 0;
        for (AttachmentObject object : objects) {
            Path path = attachmentRoot.resolve(object.key()).normalize();
            if (!path.startsWith(attachmentRoot)) continue;
            try { Files.deleteIfExists(path); }
            catch (IOException exception) { throw new IllegalStateException("Cannot delete attachment object", exception); }
            deleted += jdbc.update("DELETE FROM " + names.attachments() + " WHERE id=?", object.id());
        }
        return deleted;
    }

    private long cleanupDedup() {
        long keepGeneration = Math.floorDiv(Instant.now().minus(properties.message().automaticRetryWindow()).getEpochSecond(), 21600);
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() "
                + "AND table_name LIKE 'send\\_dedup\\_%\\_g%' ESCAPE '\\\\'", String.class);
        long deleted = 0;
        for (String table : tables) {
            int marker = table.lastIndexOf("_g");
            if (marker < 0) continue;
            long generation;
            try { generation = Long.parseLong(table.substring(marker + 2)); }
            catch (NumberFormatException ignored) { continue; }
            if (generation < keepGeneration) deleted += jdbc.update("DELETE FROM " + table);
        }
        jdbc.update("DELETE FROM dedup_generations WHERE generation<?", keepGeneration);
        return deleted;
    }

    private long recoverStaleArchiveBatches() {
        long recovered = 0;
        Timestamp cutoff = Timestamp.from(Instant.now().minus(java.time.Duration.ofHours(6)));
        for (int group = 0; group < 8; group++) {
            MessageTableNames names = new MessageTableNames(group, 0);
            recovered += jdbc.update("UPDATE " + names.archiveBatches()
                    + " SET state=6,last_error='archive worker stopped before publication',updated_at=UTC_TIMESTAMP(3) "
                    + "WHERE state IN (1,2,3) AND updated_at<?", cutoff);
        }
        return recovered;
    }

    private long deleteBatches(String sql, Timestamp cutoff) {
        long total = 0;
        int changed;
        do { changed = jdbc.update(sql, cutoff, properties.archive().batchSize()); total += changed; }
        while (changed == properties.archive().batchSize());
        return total;
    }

    private void refreshBacklog(LocalDate cutoff) {
        long count = 0;
        for (int group = 0; group < 8; group++) {
            MessageTableNames names = new MessageTableNames(group, 0);
            Long value = jdbc.queryForObject("SELECT COUNT(*) FROM (SELECT m.slot_id,m.message_day FROM "
                    + names.messages() + " m JOIN " + names.conversations()
                    + " c ON c.id=m.conversation_id AND c.write_state<>3 WHERE m.message_day<? "
                    + "GROUP BY m.slot_id,m.message_day) x", Long.class, Date.valueOf(cutoff));
            count += value == null ? 0 : value;
        }
        archiveBacklog.set(count);
        long failed = 0;
        long oldest = 0;
        for (int group = 0; group < 8; group++) {
            MessageTableNames names = new MessageTableNames(group, 0);
            Long failedCount = jdbc.queryForObject("SELECT COUNT(*) FROM " + names.archiveBatches()
                    + " WHERE state=6", Long.class);
            Long activeAge = jdbc.queryForObject("SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND,updated_at,UTC_TIMESTAMP())),0) "
                    + "FROM " + names.archiveBatches() + " WHERE state IN (1,2,3,4)", Long.class);
            failed += failedCount == null ? 0 : failedCount;
            oldest = Math.max(oldest, activeAge == null ? 0 : activeAge);
        }
        failedArchiveBatches.set(failed);
        oldestActiveArchiveSeconds.set(oldest);
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Cannot encode lifecycle details", exception); }
    }
    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(2000, value.length()));
    }
    private record Scope(int tableGroup, int slotId, LocalDate day) {}
    private record AttachmentObject(byte[] id, String key) {}
}
