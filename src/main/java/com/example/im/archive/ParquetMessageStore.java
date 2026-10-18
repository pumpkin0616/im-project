package com.example.im.archive;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Consumer;

@Component
final class ParquetMessageStore {
    private static final Schema SCHEMA = new Schema.Parser().parse("""
            {"type":"record","name":"ArchivedMessage","namespace":"com.example.im.archive.avro","fields":[
              {"name":"conversationId","type":"string"},{"name":"seq","type":"long"},
              {"name":"messageId","type":"string"},{"name":"senderId","type":"string"},
              {"name":"clientMsgId","type":"string"},{"name":"type","type":"int"},
              {"name":"status","type":"int"},{"name":"content","type":"string"},
              {"name":"recalledBy","type":["null","string"],"default":null},
              {"name":"recalledAt","type":["null","long"],"default":null},
              {"name":"createdAt","type":"long"}
            ]}
            """);

    ParquetWriter<GenericRecord> writer(Path path) throws IOException {
        return AvroParquetWriter.<GenericRecord>builder(new LocalParquetFile(path))
                .withSchema(SCHEMA)
                .withCompressionCodec(CompressionCodecName.ZSTD)
                .withRowGroupSize(128L * 1024 * 1024)
                .withPageSize(1024 * 1024)
                .build();
    }

    void write(ParquetWriter<GenericRecord> writer, ArchiveMessage message) throws IOException {
        GenericRecord row = new GenericData.Record(SCHEMA);
        row.put("conversationId", message.conversationId().toString());
        row.put("seq", message.seq());
        row.put("messageId", message.messageId().toString());
        row.put("senderId", message.senderId().toString());
        row.put("clientMsgId", message.clientMsgId().toString());
        row.put("type", message.type());
        row.put("status", message.status());
        row.put("content", message.content());
        row.put("recalledBy", message.recalledBy() == null ? null : message.recalledBy().toString());
        row.put("recalledAt", message.recalledAt() == null ? null : message.recalledAt().toEpochMilli());
        row.put("createdAt", message.createdAt().toEpochMilli());
        writer.write(row);
    }

    long read(Path path, Consumer<ArchiveMessage> consumer) throws IOException {
        long count = 0;
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(new LocalParquetFile(path)).build()) {
            GenericRecord row;
            while ((row = reader.read()) != null) {
                consumer.accept(from(row));
                count++;
            }
        }
        return count;
    }

    private ArchiveMessage from(GenericRecord row) {
        Object recalledBy = row.get("recalledBy");
        Object recalledAt = row.get("recalledAt");
        return new ArchiveMessage(UUID.fromString(row.get("conversationId").toString()),
                (Long) row.get("seq"), UUID.fromString(row.get("messageId").toString()),
                UUID.fromString(row.get("senderId").toString()), UUID.fromString(row.get("clientMsgId").toString()),
                (Integer) row.get("type"), (Integer) row.get("status"), row.get("content").toString(),
                recalledBy == null ? null : UUID.fromString(recalledBy.toString()),
                recalledAt == null ? null : Instant.ofEpochMilli((Long) recalledAt),
                Instant.ofEpochMilli((Long) row.get("createdAt")));
    }
}
