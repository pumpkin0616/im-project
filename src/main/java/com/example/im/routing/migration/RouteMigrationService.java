package com.example.im.routing.migration;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.message.infrastructure.UuidV7Generator;
import com.example.im.routing.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static com.example.im.routing.migration.MigrationDtos.*;

@Service
final class RouteMigrationService {
    private static final Logger log = LoggerFactory.getLogger(RouteMigrationService.class);
    private static final Set<String> FINISHED = Set.of("COMPLETED", "FAILED");
    private final JdbcTemplate control;
    private final TransactionTemplate controlTransactions;
    private final ConversationRouteResolver routes;
    private final RouteCacheControl cache;
    private final MigrationShardRegistry shards;
    private final UuidV7Generator ids;
    private final ObjectMapper json;
    private final int batchSize;
    private final Counter completed;
    private final Counter failed;

    RouteMigrationService(JdbcTemplate control, TransactionTemplate controlTransactions,
                          ConversationRouteResolver routes, RouteCacheControl cache,
                          MigrationShardRegistry shards, UuidV7Generator ids,
                          ObjectMapper json, ImProperties properties, MeterRegistry metrics) {
        this.control = control;
        this.controlTransactions = controlTransactions;
        this.routes = routes;
        this.cache = cache;
        this.shards = shards;
        this.ids = ids;
        this.json = json;
        this.batchSize = properties.routing().migrationBatchSize();
        this.completed = metrics.counter("im.routing.migrations", "result", "completed");
        this.failed = metrics.counter("im.routing.migrations", "result", "failed");
    }

    MigrationView start(StartMigrationRequest request) {
        validate(request);
        MigrationView created = createJob(request);
        log.info("Route migration started: id={}, scope={}, slot={}, conversation={}, source={}/{}, target={}/{}",
                created.id(), created.scopeType(), created.slotId(), created.conversationId(), created.sourceShard(),
                created.sourceTableGroup(), created.targetShard(), created.targetTableGroup());
        boolean switched = false;
        try {
            updateState(created.id(), "COPYING", Map.of("phase", "initial-copy"), null, true, false);
            List<UUID> conversations = conversations(created);
            copyAll(created, conversations);

            updateState(created.id(), "FREEZING", Map.of("conversationCount", conversations.size()), null, false, false);
            freezeSource(created, conversations);

            updateState(created.id(), "CATCHING_UP", Map.of("phase", "final-copy"), null, false, false);
            copyAll(created, conversations);
            Map<String, Object> checkpoint = verify(created, conversations);

            updateState(created.id(), "SWITCHING", checkpoint, null, false, false);
            // Stop source Outbox leasing before the target becomes visible. The source is
            // restored if the following compare-and-set route switch fails.
            markSourceMoved(created, conversations);
            switchRoute(created, conversations);
            switched = true;

            updateState(created.id(), "VERIFYING", checkpoint, null, false, false);
            verify(created, conversations);
            updateState(created.id(), "COMPLETED", checkpoint, null, false, true);
            completed.increment();
            cache.invalidateAll();
            log.info("Route migration completed: id={}, targetEpoch={}, checkpoint={}",
                    created.id(), created.targetEpoch(), checkpoint);
            return get(created.id());
        } catch (RuntimeException exception) {
            if (!switched) restoreSource(created);
            updateState(created.id(), "FAILED", Map.of("switched", switched), safeMessage(exception), false, true);
            failed.increment();
            cache.invalidateAll();
            log.error("Route migration failed: id={}, switched={}", created.id(), switched, exception);
            throw exception;
        }
    }

    MigrationView get(UUID id) {
        List<MigrationView> rows = control.query("SELECT * FROM im_control.migration_jobs WHERE id=?",
                (rs, row) -> map(rs), UuidCodec.toBytes(id));
        if (rows.isEmpty()) throw error("MIGRATION_NOT_FOUND", "Migration job does not exist", 404);
        return rows.getFirst();
    }

    private void validate(StartMigrationRequest request) {
        if (request.scopeType() == ScopeType.CONVERSATION && request.conversationId() == null) {
            throw error("INVALID_MIGRATION_SCOPE", "conversationId is required", 400);
        }
        if (request.scopeType() == ScopeType.SLOT && request.slotId() == null) {
            throw error("INVALID_MIGRATION_SCOPE", "slotId is required", 400);
        }
        try { shards.require(request.targetShard()); }
        catch (IllegalArgumentException exception) {
            throw error("TARGET_SHARD_NOT_CONFIGURED", exception.getMessage(), 422);
        }
    }

    private MigrationView createJob(StartMigrationRequest request) {
        return Objects.requireNonNull(controlTransactions.execute(status -> {
            ResolvedRoute source = request.scopeType() == ScopeType.CONVERSATION
                    ? routes.resolve(request.conversationId()) : slotRouteForUpdate(request.slotId());
            if (!source.shardId().equals(shards.localShard())) {
                throw error("SOURCE_SHARD_MISMATCH", "Run the migration on " + source.shardId(), 409);
            }
            if (source.shardId().equals(request.targetShard()) && source.tableGroup() == request.targetTableGroup()) {
                throw error("MIGRATION_TARGET_UNCHANGED", "Target route equals the source route", 422);
            }
            Integer active = control.queryForObject("SELECT COUNT(*) FROM im_control.migration_jobs WHERE "
                            + (request.scopeType() == ScopeType.SLOT ? "scope_type=1 AND slot_id=?" : "scope_type=2 AND conversation_id=?")
                            + " AND state NOT IN ('COMPLETED','FAILED')", Integer.class,
                    request.scopeType() == ScopeType.SLOT ? request.slotId() : UuidCodec.toBytes(request.conversationId()));
            if (active != null && active > 0) throw error("MIGRATION_ALREADY_RUNNING", "The route already has an active migration", 409);

            long targetEpoch = Math.addExact(source.routeEpoch(), 1);
            UUID id = ids.next();
            Instant now = Instant.now();
            control.update("INSERT INTO im_control.migration_jobs(id,scope_type,slot_id,conversation_id,source_shard,"
                            + "source_table_group,target_shard,target_table_group,source_epoch,target_epoch,state,checkpoint,"
                            + "created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,'CREATED',CAST(? AS JSON),?,?)",
                    UuidCodec.toBytes(id), request.scopeType() == ScopeType.SLOT ? 1 : 2, source.slotId(),
                    request.conversationId() == null ? null : UuidCodec.toBytes(request.conversationId()),
                    source.shardId(), source.tableGroup(), request.targetShard(), request.targetTableGroup(),
                    source.routeEpoch(), targetEpoch, "{}", Timestamp.from(now), Timestamp.from(now));

            if (request.scopeType() == ScopeType.SLOT) {
                int changed = control.update("UPDATE im_control.slot_routes SET state=2,updated_at=UTC_TIMESTAMP(3) "
                                + "WHERE slot_id=? AND route_epoch=? AND state=1", source.slotId(), source.routeEpoch());
                if (changed != 1) throw error("ROUTE_STALE", "Slot route changed before migration started", 409);
            } else {
                control.update("INSERT INTO im_control.conversation_routes(conversation_id,shard_id,table_group,route_epoch,state,updated_at) "
                                + "VALUES(?,?,?,?,2,UTC_TIMESTAMP(3)) ON DUPLICATE KEY UPDATE shard_id=VALUES(shard_id),"
                                + "table_group=VALUES(table_group),route_epoch=VALUES(route_epoch),state=2,updated_at=VALUES(updated_at)",
                        UuidCodec.toBytes(request.conversationId()), source.shardId(), source.tableGroup(), source.routeEpoch());
                JdbcTemplate sourceJdbc = shards.require(source.shardId());
                sourceJdbc.update("INSERT INTO conversation_fences(conversation_id,route_epoch,writable) VALUES(?,?,TRUE) "
                                + "ON DUPLICATE KEY UPDATE route_epoch=VALUES(route_epoch),writable=TRUE",
                        UuidCodec.toBytes(request.conversationId()), source.routeEpoch());
            }
            bumpVersion();
            return new MigrationView(id, request.scopeType(), source.slotId(), request.conversationId(),
                    source.shardId(), source.tableGroup(), request.targetShard(), request.targetTableGroup(),
                    source.routeEpoch(), targetEpoch, "CREATED", Map.of(), null, now, now, null, null);
        }));
    }

    private ResolvedRoute slotRouteForUpdate(int slotId) {
        List<ResolvedRoute> rows = control.query("SELECT shard_id,table_group,route_epoch,state FROM im_control.slot_routes "
                        + "WHERE slot_id=? FOR UPDATE", (rs, row) -> new ResolvedRoute(slotId, rs.getString(1),
                rs.getInt(2), rs.getLong(3), RouteState.fromDatabase(rs.getInt(4))), slotId);
        if (rows.isEmpty()) throw error("ROUTE_NOT_PROVISIONED", "Slot route does not exist", 404);
        if (rows.getFirst().state() != RouteState.ACTIVE) throw error("ROUTE_NOT_ACTIVE", "Slot route is not active", 409);
        return rows.getFirst();
    }

    private List<UUID> conversations(MigrationView migration) {
        if (migration.scopeType() == ScopeType.CONVERSATION) return List.of(migration.conversationId());
        JdbcTemplate source = shards.require(migration.sourceShard());
        MessageTableNames names = new MessageTableNames(migration.sourceTableGroup(), 0);
        List<UUID> ids = source.query("SELECT id FROM " + names.conversations() + " WHERE slot_id=?",
                (rs, row) -> UuidCodec.fromBytes(rs.getBytes(1)), migration.slotId());
        if (ids.isEmpty()) return ids;
        String placeholders = String.join(",", ids.stream().map(ignored -> "?").toList());
        Set<UUID> overrides = new HashSet<>(control.query("SELECT conversation_id FROM im_control.conversation_routes "
                        + "WHERE conversation_id IN (" + placeholders + ")",
                (rs, row) -> UuidCodec.fromBytes(rs.getBytes(1)), ids.stream().map(UuidCodec::toBytes).toArray()));
        return ids.stream().filter(id -> !overrides.contains(id)).toList();
    }

    private void copyAll(MigrationView migration, List<UUID> conversations) {
        JdbcTemplate source = shards.require(migration.sourceShard());
        JdbcTemplate target = shards.require(migration.targetShard());
        MessageTableNames from = new MessageTableNames(migration.sourceTableGroup(), 0);
        MessageTableNames to = new MessageTableNames(migration.targetTableGroup(), 0);
        for (UUID conversation : conversations) copyConversation(source, target, from, to, conversation);
    }

    private void copyConversation(JdbcTemplate source, JdbcTemplate target, MessageTableNames from,
                                  MessageTableNames to, UUID conversationId) {
        byte[] id = UuidCodec.toBytes(conversationId);
        copy(source, target, from.conversations(), to.conversations(), "id=?", new Object[]{id},
                columns("id,slot_id,route_epoch,write_state,type,direct_key,title,owner_id,status,delivery_mode,last_seq,min_available_seq,member_version,last_message_at,created_at,updated_at"),
                columns("slot_id,route_epoch,write_state,type,direct_key,title,owner_id,status,delivery_mode,last_seq,min_available_seq,member_version,last_message_at,updated_at"));
        copy(source, target, from.members(), to.members(), "conversation_id=?", new Object[]{id},
                columns("conversation_id,user_id,slot_id,role,status,membership_version,joined_seq,last_read_seq,joined_at,left_at"),
                columns("slot_id,role,status,membership_version,joined_seq,last_read_seq,joined_at,left_at"));
        copy(source, target, from.messages(), to.messages(), "conversation_id=?", new Object[]{id},
                columns("conversation_id,message_day,seq,slot_id,message_id,sender_id,client_msg_id,type,status,content,recalled_by,recalled_at,created_at"),
                columns("slot_id,message_id,sender_id,client_msg_id,type,status,content,recalled_by,recalled_at,created_at"));
        copy(source, target, from.attachments(), to.attachments(), "conversation_id=?", new Object[]{id},
                columns("id,conversation_id,slot_id,owner_id,object_key,file_name,mime_type,size_bytes,sha256,status,bound_seq,expires_at,created_at"),
                columns("conversation_id,slot_id,owner_id,object_key,file_name,mime_type,size_bytes,sha256,status,bound_seq,expires_at"));
        copy(source, target, from.outbox(), to.outbox(), "conversation_id=?", new Object[]{id},
                columns("event_id,event_type,schema_version,slot_id,conversation_id,message_day,message_seq,payload,status,attempts,available_at,lease_owner,lease_until,created_at,published_at"),
                columns("event_type,schema_version,slot_id,conversation_id,message_day,message_seq,payload,status,attempts,available_at,lease_owner,lease_until,created_at,published_at"));
        copyDedup(source, target, from.tableGroup(), to.tableGroup(), id);
    }

    private void copyDedup(JdbcTemplate source, JdbcTemplate target, int sourceGroup, int targetGroup, byte[] conversationId) {
        String prefix = String.format("send_dedup_%02d_g", sourceGroup);
        List<String> tables = source.queryForList("SELECT table_name FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name LIKE ?", String.class, prefix + "%");
        for (String sourceTable : tables) {
            String generation = sourceTable.substring(prefix.length());
            if (!generation.chars().allMatch(Character::isDigit)) continue;
            String targetTable = String.format("send_dedup_%02d_g%s", targetGroup, generation);
            Integer ready = target.queryForObject("SELECT COUNT(*) FROM information_schema.tables "
                    + "WHERE table_schema=DATABASE() AND table_name=?", Integer.class, targetTable);
            if (ready == null || ready != 1) {
                throw error("TARGET_SCHEMA_NOT_READY", "Missing pre-provisioned table " + targetTable, 409);
            }
            copy(source, target, sourceTable, targetTable, "conversation_id=?", new Object[]{conversationId},
                    columns("conversation_id,sender_id,client_msg_id,slot_id,request_hash,message_id,seq,message_day,created_at"),
                    columns("slot_id,request_hash,message_id,seq,message_day,created_at"));
        }
    }

    private void copy(JdbcTemplate source, JdbcTemplate target, String sourceTable, String targetTable,
                      String where, Object[] args, List<String> columns, List<String> updates) {
        String select = "SELECT " + String.join(",", columns) + " FROM " + sourceTable + " WHERE " + where;
        String insert = "INSERT INTO " + targetTable + "(" + String.join(",", columns) + ") VALUES ("
                + String.join(",", columns.stream().map(ignored -> "?").toList()) + ") ON DUPLICATE KEY UPDATE "
                + String.join(",", updates.stream().map(column -> column + "=VALUES(" + column + ")").toList());
        source.query(connection -> {
            var statement = connection.prepareStatement(select, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setFetchSize(batchSize);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, rs -> {
            List<Object[]> batch = new ArrayList<>(batchSize);
            while (rs.next()) {
                Object[] row = new Object[columns.size()];
                for (int index = 0; index < columns.size(); index++) row[index] = rs.getObject(columns.get(index));
                batch.add(row);
                if (batch.size() == batchSize) { target.batchUpdate(insert, batch); batch.clear(); }
            }
            if (!batch.isEmpty()) target.batchUpdate(insert, batch);
            return null;
        });
    }

    private void freezeSource(MigrationView migration, List<UUID> conversations) {
        JdbcTemplate source = shards.require(migration.sourceShard());
        MessageTableNames names = new MessageTableNames(migration.sourceTableGroup(), 0);
        if (migration.scopeType() == ScopeType.SLOT) {
            int changed = source.update("UPDATE slot_fences SET writable=FALSE WHERE slot_id=? AND route_epoch=?",
                    migration.slotId(), migration.sourceEpoch());
            if (changed != 1) throw error("SOURCE_FENCE_STALE", "Source slot fence changed", 409);
        } else {
            int changed = source.update("UPDATE conversation_fences SET writable=FALSE WHERE conversation_id=? AND route_epoch=?",
                    UuidCodec.toBytes(migration.conversationId()), migration.sourceEpoch());
            if (changed != 1) throw error("SOURCE_FENCE_STALE", "Source conversation fence changed", 409);
        }
        for (UUID conversation : conversations) {
            int changed = source.update("UPDATE " + names.conversations() + " SET write_state=2,updated_at=UTC_TIMESTAMP(3) "
                            + "WHERE id=? AND route_epoch=? AND write_state IN (1,2)",
                    UuidCodec.toBytes(conversation), migration.sourceEpoch());
            if (changed != 1) throw error("SOURCE_CONVERSATION_STALE", "Source conversation changed before freeze", 409);
        }
    }

    private Map<String, Object> verify(MigrationView migration, List<UUID> conversations) {
        JdbcTemplate source = shards.require(migration.sourceShard());
        JdbcTemplate target = shards.require(migration.targetShard());
        MessageTableNames from = new MessageTableNames(migration.sourceTableGroup(), 0);
        MessageTableNames to = new MessageTableNames(migration.targetTableGroup(), 0);
        long messages = 0, members = 0, attachments = 0;
        for (UUID conversation : conversations) {
            byte[] id = UuidCodec.toBytes(conversation);
            messages += equalCount(source, target, from.messages(), to.messages(), id);
            members += equalCount(source, target, from.members(), to.members(), id);
            attachments += equalCount(source, target, from.attachments(), to.attachments(), id);
            Long sourceHigh = source.queryForObject("SELECT last_seq FROM " + from.conversations() + " WHERE id=?", Long.class, id);
            Long targetHigh = target.queryForObject("SELECT last_seq FROM " + to.conversations() + " WHERE id=?", Long.class, id);
            if (!Objects.equals(sourceHigh, targetHigh)) throw error("MIGRATION_VERIFY_FAILED", "Conversation high watermark differs", 409);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("conversationCount", conversations.size());
        result.put("messageCount", messages);
        result.put("memberCount", members);
        result.put("attachmentCount", attachments);
        result.put("verifiedAt", Instant.now().toString());
        return result;
    }

    private long equalCount(JdbcTemplate source, JdbcTemplate target, String from, String to, byte[] id) {
        Long left = source.queryForObject("SELECT COUNT(*) FROM " + from + " WHERE conversation_id=?", Long.class, id);
        Long right = target.queryForObject("SELECT COUNT(*) FROM " + to + " WHERE conversation_id=?", Long.class, id);
        if (!Objects.equals(left, right)) throw error("MIGRATION_VERIFY_FAILED", from + " row count differs", 409);
        return left == null ? 0 : left;
    }

    private void switchRoute(MigrationView migration, List<UUID> conversations) {
        JdbcTemplate target = shards.require(migration.targetShard());
        MessageTableNames targetTables = new MessageTableNames(migration.targetTableGroup(), 0);
        if (migration.scopeType() == ScopeType.SLOT) {
            target.update("INSERT INTO slot_fences(slot_id,route_epoch,writable) VALUES(?,?,TRUE) "
                            + "ON DUPLICATE KEY UPDATE route_epoch=VALUES(route_epoch),writable=TRUE",
                    migration.slotId(), migration.targetEpoch());
        } else {
            target.update("INSERT INTO conversation_fences(conversation_id,route_epoch,writable) VALUES(?,?,TRUE) "
                            + "ON DUPLICATE KEY UPDATE route_epoch=VALUES(route_epoch),writable=TRUE",
                    UuidCodec.toBytes(migration.conversationId()), migration.targetEpoch());
        }
        for (UUID conversation : conversations) {
            target.update("UPDATE " + targetTables.conversations() + " SET route_epoch=?,"
                            + "write_state=IF(status=1,1,2),updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                    migration.targetEpoch(), UuidCodec.toBytes(conversation));
        }
        controlTransactions.executeWithoutResult(status -> {
            int changed;
            if (migration.scopeType() == ScopeType.SLOT) {
                changed = control.update("UPDATE im_control.slot_routes SET shard_id=?,table_group=?,route_epoch=?,state=1,"
                                + "updated_at=UTC_TIMESTAMP(3) WHERE slot_id=? AND route_epoch=? AND state=2",
                        migration.targetShard(), migration.targetTableGroup(), migration.targetEpoch(),
                        migration.slotId(), migration.sourceEpoch());
            } else {
                changed = control.update("UPDATE im_control.conversation_routes SET shard_id=?,table_group=?,route_epoch=?,state=1,"
                                + "updated_at=UTC_TIMESTAMP(3) WHERE conversation_id=? AND route_epoch=? AND state=2",
                        migration.targetShard(), migration.targetTableGroup(), migration.targetEpoch(),
                        UuidCodec.toBytes(migration.conversationId()), migration.sourceEpoch());
            }
            if (changed != 1) throw error("ROUTE_CAS_FAILED", "Route changed during migration switch", 409);
            bumpVersion();
        });
        cache.invalidateAll();
    }

    private void markSourceMoved(MigrationView migration, List<UUID> conversations) {
        JdbcTemplate source = shards.require(migration.sourceShard());
        MessageTableNames tables = new MessageTableNames(migration.sourceTableGroup(), 0);
        for (UUID conversation : conversations) {
            source.update("UPDATE " + tables.conversations() + " SET write_state=3,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                    UuidCodec.toBytes(conversation));
        }
    }

    private void restoreSource(MigrationView migration) {
        try {
            JdbcTemplate source = shards.require(migration.sourceShard());
            MessageTableNames tables = new MessageTableNames(migration.sourceTableGroup(), 0);
            if (migration.scopeType() == ScopeType.SLOT) {
                source.update("UPDATE slot_fences SET route_epoch=?,writable=TRUE WHERE slot_id=?",
                        migration.sourceEpoch(), migration.slotId());
                source.update("UPDATE " + tables.conversations() + " SET write_state=IF(status=1,1,2) WHERE slot_id=? AND route_epoch=?",
                        migration.slotId(), migration.sourceEpoch());
                control.update("UPDATE im_control.slot_routes SET state=1,updated_at=UTC_TIMESTAMP(3) WHERE slot_id=? AND route_epoch=?",
                        migration.slotId(), migration.sourceEpoch());
            } else {
                source.update("UPDATE conversation_fences SET route_epoch=?,writable=TRUE WHERE conversation_id=?",
                        migration.sourceEpoch(), UuidCodec.toBytes(migration.conversationId()));
                source.update("UPDATE " + tables.conversations() + " SET write_state=IF(status=1,1,2) WHERE id=? AND route_epoch=?",
                        UuidCodec.toBytes(migration.conversationId()), migration.sourceEpoch());
                control.update("UPDATE im_control.conversation_routes SET state=1,updated_at=UTC_TIMESTAMP(3) "
                                + "WHERE conversation_id=? AND route_epoch=?",
                        UuidCodec.toBytes(migration.conversationId()), migration.sourceEpoch());
            }
            bumpVersion();
        } catch (RuntimeException ignored) {
            // The job remains FAILED with the original error. Operators can inspect fences before retrying.
        }
    }

    private void updateState(UUID id, String state, Map<String, Object> checkpoint, String error,
                             boolean started, boolean ended) {
        control.update("UPDATE im_control.migration_jobs SET state=?,checkpoint=CAST(? AS JSON),last_error=?,"
                        + "started_at=IF(? AND started_at IS NULL,UTC_TIMESTAMP(3),started_at),"
                        + "completed_at=IF(?,UTC_TIMESTAMP(3),completed_at),updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                state, encode(checkpoint), error, started, ended, UuidCodec.toBytes(id));
    }

    private void bumpVersion() {
        control.update("UPDATE im_control.routing_metadata SET route_version=route_version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=1");
    }

    private MigrationView map(ResultSet rs) throws SQLException {
        return new MigrationView(UuidCodec.fromBytes(rs.getBytes("id")), rs.getInt("scope_type") == 1
                ? ScopeType.SLOT : ScopeType.CONVERSATION, rs.getInt("slot_id"), uuid(rs.getBytes("conversation_id")),
                rs.getString("source_shard"), rs.getInt("source_table_group"), rs.getString("target_shard"),
                rs.getInt("target_table_group"), rs.getLong("source_epoch"), rs.getLong("target_epoch"),
                rs.getString("state"), decode(rs.getString("checkpoint")), rs.getString("last_error"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("completed_at")));
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException("Cannot encode migration checkpoint", exception); }
    }

    private Map<String, Object> decode(String value) {
        if (value == null) return Map.of();
        try { return json.readValue(value, new TypeReference<>() {}); }
        catch (JacksonException exception) { throw new IllegalStateException("Cannot decode migration checkpoint", exception); }
    }

    private static List<String> columns(String value) { return Arrays.asList(value.split(",")); }
    private static UUID uuid(byte[] value) { return value == null ? null : UuidCodec.fromBytes(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static String safeMessage(Throwable error) {
        String value = error.getMessage();
        return value == null ? error.getClass().getSimpleName() : value.substring(0, Math.min(value.length(), 2000));
    }
    private static ApiBusinessException error(String code, String message, int status) {
        return new ApiBusinessException(code, message, status);
    }
}
