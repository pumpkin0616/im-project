package com.example.im.routing;

public record MessageTableNames(int tableGroup, long dedupGeneration) {
    public MessageTableNames {
        if (tableGroup < 0 || tableGroup > 7) {
            throw new IllegalArgumentException("tableGroup must be between 0 and 7");
        }
        if (dedupGeneration < 0) {
            throw new IllegalArgumentException("dedupGeneration must not be negative");
        }
    }

    public String conversations() {
        return "conversations_" + groupSuffix();
    }

    public String members() {
        return "members_" + groupSuffix();
    }

    public String messages() {
        return "messages_" + groupSuffix();
    }

    public String attachments() {
        return "attachments_" + groupSuffix();
    }

    public String outbox() {
        return "outbox_" + groupSuffix();
    }

    public String archiveBatches() { return "archive_batches_" + groupSuffix(); }

    public String archiveSegments() { return "archive_segments_" + groupSuffix(); }

    public String dedup() {
        return "send_dedup_" + groupSuffix() + "_g" + dedupGeneration;
    }

    private String groupSuffix() {
        return String.format("%02d", tableGroup);
    }
}

