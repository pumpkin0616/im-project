package com.example.im.archive;

import com.example.im.config.ImProperties;
import com.example.im.routing.MessageTableNames;
import com.example.im.routing.UuidCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public final class ArchiveReader {
    private final JdbcTemplate jdbc;
    private final ParquetMessageStore parquet;
    private final Path root;

    ArchiveReader(JdbcTemplate jdbc, ParquetMessageStore parquet, ImProperties properties) {
        this.jdbc = jdbc;
        this.parquet = parquet;
        this.root = properties.archive().root().toAbsolutePath().normalize();
    }

    public List<ArchiveMessage> list(int tableGroup, UUID conversationId, long minimum, long maximum,
                                     boolean forward, int limit) {
        if (minimum > maximum || limit <= 0) return List.of();
        MessageTableNames names = new MessageTableNames(tableGroup, 0);
        List<String> keys = jdbc.query("SELECT DISTINCT s.object_key FROM " + names.archiveSegments() + " s JOIN "
                        + names.archiveBatches() + " b ON b.id=s.batch_id WHERE s.conversation_id=? "
                        + "AND s.max_seq>=? AND s.min_seq<=? AND b.state IN (4,5)",
                (rs, n) -> rs.getString(1), UuidCodec.toBytes(conversationId), minimum, maximum);
        Map<Long, ArchiveMessage> values = new LinkedHashMap<>();
        for (String key : keys) {
            try {
                parquet.read(resolve(key), message -> {
                    if (message.conversationId().equals(conversationId)
                            && message.seq() >= minimum && message.seq() <= maximum) {
                        values.putIfAbsent(message.seq(), message);
                    }
                });
            } catch (IOException exception) {
                throw new IllegalStateException("Cannot read archived message object " + key, exception);
            }
        }
        Comparator<ArchiveMessage> order = Comparator.comparingLong(ArchiveMessage::seq);
        if (!forward) order = order.reversed();
        return values.values().stream().sorted(order).limit(limit).toList();
    }

    public Optional<ArchiveMessage> find(int tableGroup, UUID conversationId, long sequence) {
        return list(tableGroup, conversationId, sequence, sequence, true, 1).stream().findFirst();
    }

    public boolean recalled(int tableGroup, UUID conversationId, long sequence) {
        return find(tableGroup, conversationId, sequence).map(value -> value.status() == 2).orElse(false);
    }

    private Path resolve(String key) {
        Path value = root.resolve(key).normalize();
        if (!value.startsWith(root)) throw new IllegalArgumentException("Archive object key escapes its root");
        return value;
    }
}
