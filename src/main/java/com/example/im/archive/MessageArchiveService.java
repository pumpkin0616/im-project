package com.example.im.archive;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.UuidCodec;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.hadoop.ParquetWriter;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.im.archive.ArchiveDtos.*;

@Service
final class MessageArchiveService {
    private static final Logger log = LoggerFactory.getLogger(MessageArchiveService.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ParquetMessageStore parquet;
    private final ObjectMapper json;
    private final UuidV7Generator ids;
    private final Path root;
    private final String shardId;
    private final int deleteBatchSize;
    private final Counter completed;
    private final Counter failed;

    MessageArchiveService(JdbcTemplate jdbc, TransactionTemplate transactions, ParquetMessageStore parquet,
                          ObjectMapper json, UuidV7Generator ids, ImProperties properties,
                          MeterRegistry metrics) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.parquet = parquet;
        this.json = json;
        this.ids = ids;
        this.root = properties.archive().root().toAbsolutePath().normalize();
        this.shardId = properties.routing().localShardId();
        this.deleteBatchSize = properties.archive().batchSize();
        this.completed = metrics.counter("im.lifecycle.archives", "result", "completed");
        this.failed = metrics.counter("im.lifecycle.archives", "result", "failed");
    }

    ArchiveBatchView archive(ArchiveRequest request) {
        if (!request.messageDay().isBefore(LocalDate.now(ZoneOffset.UTC))) {
            throw error("ARCHIVE_DAY_NOT_CLOSED", "Only a completed UTC day can be archived", 422);
        }
        assertRouteStable(request.slotId());
        MessageTableNames names = new MessageTableNames(request.tableGroup(), 0);
        List<UUID> published = jdbc.query("SELECT id FROM " + names.archiveBatches()
                        + " WHERE slot_id=? AND message_day=? AND state=4 ORDER BY generation DESC LIMIT 1",
                (rs, n) -> UuidCodec.fromBytes(rs.getBytes(1)), request.slotId(), Date.valueOf(request.messageDay()));
        if (!published.isEmpty()) {
            dropHotRows(names, request.messageDay(), published.getFirst());
            jdbc.update("UPDATE " + names.archiveBatches()
                    + " SET state=5,last_error=NULL,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state=4",
                    UuidCodec.toBytes(published.getFirst()));
            completed.increment();
            return get(request.tableGroup(), published.getFirst());
        }
        Long pending = jdbc.queryForObject("SELECT COUNT(*) FROM " + names.outbox()
                        + " WHERE slot_id=? AND message_day=? AND status IN (0,1)", Long.class,
                request.slotId(), Date.valueOf(request.messageDay()));
        if (pending != null && pending > 0) {
            throw error("ARCHIVE_OUTBOX_PENDING", "Messages still have unpublished outbox events", 409);
        }
        Long recallable = jdbc.queryForObject("SELECT COUNT(*) FROM " + names.messages()
                        + " WHERE slot_id=? AND message_day=? AND created_at>TIMESTAMPADD(MINUTE,-2,UTC_TIMESTAMP(3))",
                Long.class, request.slotId(), Date.valueOf(request.messageDay()));
        if (recallable != null && recallable > 0) {
            throw error("ARCHIVE_RECALL_WINDOW_OPEN", "Messages are still inside the recall window", 409);
        }

        int generation = nextGeneration(names, request.slotId(), request.messageDay());
        UUID batchId = ids.next();
        Instant now = Instant.now();
        log.info("Archive batch started: id={}, group={}, slot={}, day={}, generation={}",
                batchId, request.tableGroup(), request.slotId(), request.messageDay(), generation);
        try {
            jdbc.update("INSERT INTO " + names.archiveBatches()
                            + "(id,slot_id,message_day,generation,state,row_count,attempt_count,created_at,updated_at) "
                            + "VALUES(?,?,?,?,1,0,1,?,?)", UuidCodec.toBytes(batchId), request.slotId(),
                    Date.valueOf(request.messageDay()), generation, Timestamp.from(now), Timestamp.from(now));
        } catch (DuplicateKeyException exception) {
            throw error("ARCHIVE_ALREADY_RUNNING", "An archive generation already exists for this scope", 409);
        }

        String prefix = String.format("archive/v1/shard=%s/group=%02d/slot=%04d/day=%s/generation=%d",
                shardId, request.tableGroup(), request.slotId(), request.messageDay(), generation);
        try {
            List<ConversationRange> ranges = ranges(names, request.slotId(), request.messageDay());
            List<Segment> segments = new ArrayList<>();
            long total = 0;
            int part = 0;
            for (ConversationRange range : ranges) {
                String key = prefix + "/conversation=" + range.conversationId() + "/part-"
                        + String.format("%05d", part++) + ".parquet";
                Segment segment = writeSegment(names, request, range, key);
                segments.add(segment);
                total += segment.rowCount();
            }
            if (total != ranges.stream().mapToLong(ConversationRange::rowCount).sum()) {
                throw new IllegalStateException("Archive row count changed during export");
            }
            jdbc.update("UPDATE " + names.archiveBatches()
                    + " SET state=2,row_count=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state=1",
                    total, UuidCodec.toBytes(batchId));

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("batchId", batchId.toString());
            manifest.put("shardId", shardId);
            manifest.put("tableGroup", request.tableGroup());
            manifest.put("slotId", request.slotId());
            manifest.put("messageDay", request.messageDay().toString());
            manifest.put("generation", generation);
            manifest.put("rowCount", total);
            manifest.put("segments", segments);
            byte[] manifestBytes = json.writeValueAsBytes(manifest);
            String manifestKey = prefix + "/manifest.json";
            writeImmutable(manifestKey, manifestBytes);
            byte[] manifestHash = sha256(manifestBytes);
            jdbc.update("UPDATE " + names.archiveBatches()
                    + " SET state=3,manifest_key=?,manifest_sha256=?,verified_at=UTC_TIMESTAMP(3),"
                    + "updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state=2", manifestKey, manifestHash,
                    UuidCodec.toBytes(batchId));

            transactions.executeWithoutResult(status -> {
                for (Segment segment : segments) {
                    jdbc.update("INSERT INTO " + names.archiveSegments()
                                    + "(conversation_id,min_seq,max_seq,slot_id,batch_id,object_key,object_sha256,"
                                    + "byte_size,row_group_no,row_count) VALUES(?,?,?,?,?,?,?,?,0,?)",
                            UuidCodec.toBytes(segment.conversationId()), segment.minSeq(), segment.maxSeq(),
                            request.slotId(), UuidCodec.toBytes(batchId), segment.objectKey(),
                            HexFormat.of().parseHex(segment.sha256()), segment.byteSize(), segment.rowCount());
                }
                jdbc.update("UPDATE " + names.archiveBatches()
                        + " SET state=4,published_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3) "
                        + "WHERE id=? AND state=3", UuidCodec.toBytes(batchId));
            });

            dropHotRows(names, request.messageDay(), batchId);
            jdbc.update("UPDATE " + names.archiveBatches()
                    + " SET state=5,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state=4", UuidCodec.toBytes(batchId));
            completed.increment();
            log.info("Archive batch completed: id={}, rows={}, manifest={}", batchId, total, manifestKey);
            return get(request.tableGroup(), batchId);
        } catch (Exception | LinkageError exception) {
            failed.increment();
            jdbc.update("UPDATE " + names.archiveBatches()
                            + " SET state=IF(state=4,4,6),last_error=?,updated_at=UTC_TIMESTAMP(3) WHERE id=? AND state<>5",
                    safeMessage(exception), UuidCodec.toBytes(batchId));
            Integer state = jdbc.queryForObject("SELECT state FROM " + names.archiveBatches() + " WHERE id=?",
                    Integer.class, UuidCodec.toBytes(batchId));
            if (state != null && state == 6) deleteUnpublished(prefix);
            log.error("Archive batch failed: id={}, group={}, slot={}, day={}", batchId,
                    request.tableGroup(), request.slotId(), request.messageDay(), exception);
            if (exception instanceof ApiBusinessException business) throw business;
            if (exception instanceof Error error) throw error;
            throw new IllegalStateException("Archive batch failed: " + batchId, exception);
        }
    }

    ArchiveBatchView get(int tableGroup, UUID batchId) {
        MessageTableNames names = new MessageTableNames(tableGroup, 0);
        List<ArchiveBatchView> values = jdbc.query("SELECT * FROM " + names.archiveBatches() + " WHERE id=?",
                (rs, n) -> new ArchiveBatchView(batchId, tableGroup, rs.getInt("slot_id"),
                        rs.getDate("message_day").toLocalDate(), rs.getInt("generation"),
                        stateName(rs.getInt("state")), rs.getLong("row_count"), rs.getString("manifest_key"),
                        hex(rs.getBytes("manifest_sha256")), rs.getString("last_error"),
                        rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("verified_at")),
                        instant(rs.getTimestamp("published_at"))), UuidCodec.toBytes(batchId));
        if (values.isEmpty()) throw error("ARCHIVE_BATCH_NOT_FOUND", "Archive batch does not exist", 404);
        return values.getFirst();
    }

    private Segment writeSegment(MessageTableNames names, ArchiveRequest request, ConversationRange range,
                                 String objectKey) throws IOException {
        Path destination = resolve(objectKey);
        Path temporary = destination.resolveSibling(destination.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(temporary.getParent());
        long[] written = {0};
        try (ParquetWriter<GenericRecord> writer = parquet.writer(temporary)) {
            try {
                jdbc.query("SELECT conversation_id,seq,message_id,sender_id,client_msg_id,type,status,content,"
                                + "recalled_by,recalled_at,created_at FROM " + names.messages()
                                + " WHERE conversation_id=? AND slot_id=? AND message_day=? ORDER BY seq",
                        rs -> {
                            ArchiveMessage value = new ArchiveMessage(UuidCodec.fromBytes(rs.getBytes("conversation_id")),
                                    rs.getLong("seq"), UuidCodec.fromBytes(rs.getBytes("message_id")),
                                    UuidCodec.fromBytes(rs.getBytes("sender_id")),
                                    UuidCodec.fromBytes(rs.getBytes("client_msg_id")), rs.getInt("type"),
                                    rs.getInt("status"), rs.getString("content"), uuid(rs.getBytes("recalled_by")),
                                    instant(rs.getTimestamp("recalled_at")), rs.getTimestamp("created_at").toInstant());
                            try { parquet.write(writer, value); written[0]++; }
                            catch (IOException exception) { throw new UncheckedIOException(exception); }
                        }, UuidCodec.toBytes(range.conversationId()), request.slotId(), Date.valueOf(request.messageDay()));
            } catch (UncheckedIOException exception) {
                throw exception.getCause();
            }
        }
        if (written[0] != range.rowCount()) throw new IllegalStateException("Segment source row count changed");
        byte[] hash = sha256(temporary);
        long bytes = Files.size(temporary);
        moveImmutable(temporary, destination);
        long verified = parquet.read(destination, ignored -> {});
        if (verified != written[0]) throw new IllegalStateException("Parquet verification row count differs");
        return new Segment(range.conversationId(), range.minSeq(), range.maxSeq(), written[0], objectKey,
                HexFormat.of().formatHex(hash), bytes);
    }

    private List<ConversationRange> ranges(MessageTableNames names, int slotId, LocalDate day) {
        return jdbc.query("SELECT m.conversation_id,MIN(m.seq),MAX(m.seq),COUNT(*) FROM " + names.messages()
                        + " m JOIN " + names.conversations() + " c ON c.id=m.conversation_id AND c.write_state<>3"
                        + " WHERE m.slot_id=? AND m.message_day=? GROUP BY m.conversation_id ORDER BY m.conversation_id",
                (rs, n) -> new ConversationRange(UuidCodec.fromBytes(rs.getBytes(1)), rs.getLong(2),
                        rs.getLong(3), rs.getLong(4)), slotId, Date.valueOf(day));
    }

    private int nextGeneration(MessageTableNames names, int slotId, LocalDate day) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(generation),0)+1 FROM " + names.archiveBatches()
                + " WHERE slot_id=? AND message_day=?", Integer.class, slotId, Date.valueOf(day));
        return value == null ? 1 : value;
    }

    private void dropHotRows(MessageTableNames names, LocalDate day, UUID batchId) {
        List<byte[]> conversations = jdbc.query("SELECT DISTINCT conversation_id FROM " + names.archiveSegments()
                        + " WHERE batch_id=?", (rs, n) -> rs.getBytes(1), UuidCodec.toBytes(batchId));
        for (byte[] conversation : conversations) {
            int deleted;
            do {
                deleted = jdbc.update("DELETE FROM " + names.messages()
                                + " WHERE conversation_id=? AND message_day=? LIMIT ?", conversation,
                        Date.valueOf(day), deleteBatchSize);
            } while (deleted == deleteBatchSize);
        }
    }

    private void assertRouteStable(int slotId) {
        Integer movingSlot = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.slot_routes WHERE slot_id=? AND state=2",
                Integer.class, slotId);
        int moving = movingSlot == null ? 0 : movingSlot;
        for (int group = 0; group < 8 && moving == 0; group++) {
            MessageTableNames names = new MessageTableNames(group, 0);
            Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM im_control.conversation_routes cr JOIN "
                            + names.conversations() + " c ON c.id=cr.conversation_id WHERE c.slot_id=? AND cr.state=2",
                    Integer.class, slotId);
            moving += value == null ? 0 : value;
        }
        if (moving > 0) throw error("ARCHIVE_ROUTE_MIGRATING", "Route migration is active", 409);
    }

    private void writeImmutable(String key, byte[] bytes) throws IOException {
        Path target = resolve(key);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
    }

    private void moveImmutable(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException exception) { Files.move(source, target); }
    }

    private void deleteUnpublished(String prefix) {
        Path directory = resolve(prefix);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException ignored) { /* object lifecycle policy removes residual failed uploads */ }
            });
        } catch (IOException ignored) {
            // The batch is FAILED and never published, so readers cannot observe residual objects.
        }
    }

    private Path resolve(String key) {
        Path value = root.resolve(key).normalize();
        if (!value.startsWith(root)) throw new IllegalArgumentException("Archive object key escapes its root");
        return value;
    }

    private static byte[] sha256(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static byte[] sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1024 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
    private static UUID uuid(byte[] value) { return value == null ? null : UuidCodec.fromBytes(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String hex(byte[] value) { return value == null ? null : HexFormat.of().formatHex(value); }
    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(2000, value.length()));
    }
    private static String stateName(int state) {
        return switch (state) { case 1 -> "PREPARING"; case 2 -> "UPLOADED"; case 3 -> "VERIFIED";
            case 4 -> "PUBLISHED"; case 5 -> "HOT_DROPPED"; default -> "FAILED"; };
    }
    private static ApiBusinessException error(String code, String message, int status) {
        return new ApiBusinessException(code, message, status);
    }

    private record ConversationRange(UUID conversationId, long minSeq, long maxSeq, long rowCount) {}
    private record Segment(UUID conversationId, long minSeq, long maxSeq, long rowCount,
                           String objectKey, String sha256, long byteSize) {}
}
