-- IM message-shard schema for MySQL 8.4 LTS.
-- Run on every physical message shard. This file creates all eight table groups.
-- The session must use UTC so rolling deduplication generations have stable boundaries.

CREATE DATABASE IF NOT EXISTS im_message
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE im_message;

SET time_zone = '+00:00';

CREATE TABLE slot_fences (
    slot_id         SMALLINT UNSIGNED NOT NULL,
    route_epoch     BIGINT UNSIGNED NOT NULL,
    writable        BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (slot_id),
    CONSTRAINT ck_slot_fences_slot CHECK (slot_id < 1024)
) ENGINE=InnoDB;

CREATE TABLE conversation_fences (
    conversation_id BINARY(16) NOT NULL,
    route_epoch     BIGINT UNSIGNED NOT NULL,
    writable        BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (conversation_id)
) ENGINE=InnoDB;

CREATE TABLE dedup_generations (
    generation      BIGINT UNSIGNED NOT NULL,
    state           TINYINT UNSIGNED NOT NULL COMMENT '1 open, 2 closed',
    accept_until    DATETIME(3) NOT NULL,
    drop_after      DATETIME(3) NOT NULL,
    PRIMARY KEY (generation),
    CONSTRAINT ck_dedup_generations_state CHECK (state IN (1, 2))
) ENGINE=InnoDB;

CREATE TABLE conversations_00 (
    id                  BINARY(16) NOT NULL,
    slot_id             SMALLINT UNSIGNED NOT NULL,
    route_epoch         BIGINT UNSIGNED NOT NULL,
    write_state         TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 active, 2 frozen, 3 moved',
    type                TINYINT UNSIGNED NOT NULL COMMENT '1 direct, 2 group',
    direct_key          BINARY(32) NULL,
    title               VARCHAR(128) NULL,
    owner_id            BINARY(16) NULL,
    status              TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 active, 2 dissolved',
    delivery_mode       TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 direct body, 2 signal, 3 subscription',
    last_seq            BIGINT UNSIGNED NOT NULL DEFAULT 0,
    min_available_seq   BIGINT UNSIGNED NOT NULL DEFAULT 1,
    member_version      BIGINT UNSIGNED NOT NULL DEFAULT 1,
    last_message_at     DATETIME(3) NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_conversations_direct (direct_key),
    KEY idx_conversations_slot (slot_id, id),
    CONSTRAINT ck_conversations_slot CHECK (slot_id < 1024),
    CONSTRAINT ck_conversations_write_state CHECK (write_state IN (1, 2, 3)),
    CONSTRAINT ck_conversations_type CHECK (type IN (1, 2)),
    CONSTRAINT ck_conversations_status CHECK (status IN (1, 2)),
    CONSTRAINT ck_conversations_delivery CHECK (delivery_mode IN (1, 2, 3)),
    CONSTRAINT ck_conversations_direct CHECK (
        (type = 1 AND direct_key IS NOT NULL)
        OR (type = 2 AND direct_key IS NULL)
    )
) ENGINE=InnoDB;

CREATE TABLE members_00 (
    conversation_id     BINARY(16) NOT NULL,
    user_id             BINARY(16) NOT NULL,
    slot_id             SMALLINT UNSIGNED NOT NULL,
    role                TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 member, 2 admin, 3 owner',
    status              TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 active, 2 left',
    membership_version  BIGINT UNSIGNED NOT NULL,
    joined_seq          BIGINT UNSIGNED NOT NULL,
    last_read_seq       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    joined_at           DATETIME(3) NOT NULL,
    left_at             DATETIME(3) NULL,
    PRIMARY KEY (conversation_id, user_id),
    KEY idx_members_user (user_id, status, conversation_id),
    KEY idx_members_slot (slot_id, conversation_id),
    CONSTRAINT ck_members_role CHECK (role IN (1, 2, 3)),
    CONSTRAINT ck_members_status CHECK (status IN (1, 2))
) ENGINE=InnoDB;

CREATE TABLE messages_00 (
    conversation_id BINARY(16) NOT NULL,
    message_day     DATE NOT NULL,
    seq             BIGINT UNSIGNED NOT NULL,
    slot_id         SMALLINT UNSIGNED NOT NULL,
    message_id      BINARY(16) NOT NULL,
    sender_id       BINARY(16) NOT NULL,
    client_msg_id   BINARY(16) NOT NULL,
    type            TINYINT UNSIGNED NOT NULL COMMENT '1 text, 2 image, 3 file',
    status          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 normal, 2 recalled',
    content         JSON NOT NULL,
    recalled_by     BINARY(16) NULL,
    recalled_at     DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (conversation_id, message_day, seq),
    KEY idx_messages_conversation_seq (conversation_id, seq),
    KEY idx_messages_slot_export (slot_id, message_day, conversation_id, seq),
    KEY idx_messages_recalled_at (recalled_at),
    CONSTRAINT ck_messages_type CHECK (type IN (1, 2, 3)),
    CONSTRAINT ck_messages_status CHECK (status IN (1, 2))
) ENGINE=InnoDB
PARTITION BY RANGE COLUMNS(message_day) (
    PARTITION p20261017 VALUES LESS THAN ('2026-10-18'),
    PARTITION p_future VALUES LESS THAN (MAXVALUE)
);

CREATE TABLE attachments_00 (
    id              BINARY(16) NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    slot_id         SMALLINT UNSIGNED NOT NULL,
    owner_id        BINARY(16) NOT NULL,
    object_key      VARCHAR(512) CHARACTER SET ascii NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    mime_type       VARCHAR(128) CHARACTER SET ascii NOT NULL,
    size_bytes      BIGINT UNSIGNED NOT NULL,
    sha256          BINARY(32) NULL,
    status          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 upload, 1 checking, 2 ready, 3 rejected, 4 expired',
    bound_seq       BIGINT UNSIGNED NULL,
    expires_at      DATETIME(3) NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_attachments_object (object_key),
    KEY idx_attachments_conversation (conversation_id, bound_seq),
    KEY idx_attachments_cleanup (status, expires_at),
    KEY idx_attachments_slot (slot_id, id),
    CONSTRAINT ck_attachments_status CHECK (status IN (0, 1, 2, 3, 4))
) ENGINE=InnoDB;

CREATE TABLE outbox_00 (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    event_id        BINARY(16) NOT NULL,
    event_type      VARCHAR(48) CHARACTER SET ascii NOT NULL,
    schema_version  SMALLINT UNSIGNED NOT NULL DEFAULT 1,
    slot_id         SMALLINT UNSIGNED NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    message_day     DATE NULL,
    message_seq     BIGINT UNSIGNED NULL,
    payload         JSON NOT NULL,
    status          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 pending, 1 leased, 2 published, 3 manual',
    attempts        INT UNSIGNED NOT NULL DEFAULT 0,
    available_at    DATETIME(3) NOT NULL,
    lease_owner     BINARY(16) NULL,
    lease_until     DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL,
    published_at    DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event (event_id),
    KEY idx_outbox_pending (status, available_at, id),
    KEY idx_outbox_lease (status, lease_until, id),
    KEY idx_outbox_cleanup (status, published_at, id),
    KEY idx_outbox_slot (slot_id, id),
    CONSTRAINT ck_outbox_status CHECK (status IN (0, 1, 2, 3))
) ENGINE=InnoDB;

CREATE TABLE archive_batches_00 (
    id              BINARY(16) NOT NULL,
    slot_id         SMALLINT UNSIGNED NOT NULL,
    message_day     DATE NOT NULL,
    generation      INT UNSIGNED NOT NULL,
    state           TINYINT UNSIGNED NOT NULL COMMENT '1 preparing, 2 uploaded, 3 verified, 4 published, 5 hot dropped, 6 failed',
    row_count       BIGINT UNSIGNED NOT NULL DEFAULT 0,
    manifest_key    VARCHAR(512) CHARACTER SET ascii NULL,
    manifest_sha256 BINARY(32) NULL,
    attempt_count   INT UNSIGNED NOT NULL DEFAULT 0,
    last_error      TEXT NULL,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    verified_at     DATETIME(3) NULL,
    published_at    DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_archive_batches (slot_id, message_day, generation),
    KEY idx_archive_batches_state (state, message_day),
    CONSTRAINT ck_archive_batches_state CHECK (state IN (1, 2, 3, 4, 5, 6))
) ENGINE=InnoDB;

CREATE TABLE archive_segments_00 (
    conversation_id BINARY(16) NOT NULL,
    min_seq         BIGINT UNSIGNED NOT NULL,
    max_seq         BIGINT UNSIGNED NOT NULL,
    slot_id         SMALLINT UNSIGNED NOT NULL,
    batch_id        BINARY(16) NOT NULL,
    object_key      VARCHAR(512) CHARACTER SET ascii NOT NULL,
    object_sha256   BINARY(32) NOT NULL,
    byte_size       BIGINT UNSIGNED NOT NULL,
    row_group_no    INT UNSIGNED NOT NULL,
    row_count       INT UNSIGNED NOT NULL,
    PRIMARY KEY (conversation_id, min_seq, batch_id),
    KEY idx_archive_segments_batch (batch_id),
    KEY idx_archive_segments_slot (slot_id, conversation_id, min_seq),
    CONSTRAINT ck_archive_segments_range CHECK (max_seq >= min_seq)
) ENGINE=InnoDB;

CREATE TABLE lifecycle_runs (
    id              BINARY(16) NOT NULL,
    run_type        VARCHAR(32) CHARACTER SET ascii NOT NULL,
    state           VARCHAR(16) CHARACTER SET ascii NOT NULL,
    details         JSON NOT NULL,
    last_error      TEXT NULL,
    started_at      DATETIME(3) NOT NULL,
    completed_at    DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_lifecycle_runs_state (run_type, state, started_at)
) ENGINE=InnoDB;

-- Clone this table per six-hour generation, for example send_dedup_00_g000001.
CREATE TABLE send_dedup_template (
    conversation_id BINARY(16) NOT NULL,
    sender_id       BINARY(16) NOT NULL,
    client_msg_id   BINARY(16) NOT NULL,
    slot_id         SMALLINT UNSIGNED NOT NULL,
    request_hash    BINARY(32) NOT NULL,
    message_id      BINARY(16) NOT NULL,
    seq             BIGINT UNSIGNED NOT NULL,
    message_day     DATE NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (conversation_id, sender_id, client_msg_id),
    KEY idx_send_dedup_slot (slot_id, conversation_id)
) ENGINE=InnoDB;

-- Materialize the remaining seven table groups on every physical shard.
CREATE TABLE conversations_01 LIKE conversations_00;
CREATE TABLE members_01 LIKE members_00;
CREATE TABLE messages_01 LIKE messages_00;
CREATE TABLE attachments_01 LIKE attachments_00;
CREATE TABLE outbox_01 LIKE outbox_00;
CREATE TABLE archive_batches_01 LIKE archive_batches_00;
CREATE TABLE archive_segments_01 LIKE archive_segments_00;
CREATE TABLE conversations_02 LIKE conversations_00;
CREATE TABLE members_02 LIKE members_00;
CREATE TABLE messages_02 LIKE messages_00;
CREATE TABLE attachments_02 LIKE attachments_00;
CREATE TABLE outbox_02 LIKE outbox_00;
CREATE TABLE archive_batches_02 LIKE archive_batches_00;
CREATE TABLE archive_segments_02 LIKE archive_segments_00;
CREATE TABLE conversations_03 LIKE conversations_00;
CREATE TABLE members_03 LIKE members_00;
CREATE TABLE messages_03 LIKE messages_00;
CREATE TABLE attachments_03 LIKE attachments_00;
CREATE TABLE outbox_03 LIKE outbox_00;
CREATE TABLE archive_batches_03 LIKE archive_batches_00;
CREATE TABLE archive_segments_03 LIKE archive_segments_00;
CREATE TABLE conversations_04 LIKE conversations_00;
CREATE TABLE members_04 LIKE members_00;
CREATE TABLE messages_04 LIKE messages_00;
CREATE TABLE attachments_04 LIKE attachments_00;
CREATE TABLE outbox_04 LIKE outbox_00;
CREATE TABLE archive_batches_04 LIKE archive_batches_00;
CREATE TABLE archive_segments_04 LIKE archive_segments_00;
CREATE TABLE conversations_05 LIKE conversations_00;
CREATE TABLE members_05 LIKE members_00;
CREATE TABLE messages_05 LIKE messages_00;
CREATE TABLE attachments_05 LIKE attachments_00;
CREATE TABLE outbox_05 LIKE outbox_00;
CREATE TABLE archive_batches_05 LIKE archive_batches_00;
CREATE TABLE archive_segments_05 LIKE archive_segments_00;
CREATE TABLE conversations_06 LIKE conversations_00;
CREATE TABLE members_06 LIKE members_00;
CREATE TABLE messages_06 LIKE messages_00;
CREATE TABLE attachments_06 LIKE attachments_00;
CREATE TABLE outbox_06 LIKE outbox_00;
CREATE TABLE archive_batches_06 LIKE archive_batches_00;
CREATE TABLE archive_segments_06 LIKE archive_segments_00;
CREATE TABLE conversations_07 LIKE conversations_00;
CREATE TABLE members_07 LIKE members_00;
CREATE TABLE messages_07 LIKE messages_00;
CREATE TABLE attachments_07 LIKE attachments_00;
CREATE TABLE outbox_07 LIKE outbox_00;
CREATE TABLE archive_batches_07 LIKE archive_batches_00;
CREATE TABLE archive_segments_07 LIKE archive_segments_00;

-- Provision the rolling six-hour idempotency windows before application traffic reaches a shard.
DELIMITER $$
CREATE PROCEDURE provision_dedup_generation(IN p_generation BIGINT UNSIGNED)
SQL SECURITY DEFINER
BEGIN
    DECLARE v_group INT DEFAULT 0;
    DECLARE v_sql TEXT;
    DECLARE v_lock_acquired INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF v_lock_acquired = 1 THEN DO RELEASE_LOCK('im_dedup_generation_provision'); END IF;
        RESIGNAL;
    END;

    SELECT GET_LOCK('im_dedup_generation_provision', 5) INTO v_lock_acquired;
    IF v_lock_acquired <> 1 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='dedup generation provisioning is already running';
    END IF;

    WHILE v_group < 8 DO
        SET v_sql = CONCAT(
            'CREATE TABLE IF NOT EXISTS send_dedup_',
            LPAD(v_group, 2, '0'), '_g', p_generation,
            ' LIKE send_dedup_template'
        );
        SET @dedup_ddl = v_sql;
        PREPARE dedup_statement FROM @dedup_ddl;
        EXECUTE dedup_statement;
        DEALLOCATE PREPARE dedup_statement;
        SET v_group = v_group + 1;
    END WHILE;

    INSERT INTO dedup_generations(generation, state, accept_until, drop_after)
    VALUES (
        p_generation,
        1,
        FROM_UNIXTIME((p_generation + 1) * 21600 + 86400),
        FROM_UNIXTIME((p_generation + 1) * 21600 + 172800)
    )
    ON DUPLICATE KEY UPDATE
        accept_until = VALUES(accept_until),
        drop_after = VALUES(drop_after);

    DO RELEASE_LOCK('im_dedup_generation_provision');
END$$
DELIMITER ;

SET @current_dedup_generation = FLOOR(UNIX_TIMESTAMP(UTC_TIMESTAMP()) / 21600);
CALL provision_dedup_generation(@current_dedup_generation - 4);
CALL provision_dedup_generation(@current_dedup_generation - 3);
CALL provision_dedup_generation(@current_dedup_generation - 2);
CALL provision_dedup_generation(@current_dedup_generation - 1);
CALL provision_dedup_generation(@current_dedup_generation);
CALL provision_dedup_generation(@current_dedup_generation + 1);

-- Initialize the writable slot fences on each physical shard. For the initial
-- four-shard layout, call with shard indexes 0, 1, 2, and 3 respectively.
DELIMITER $$
CREATE PROCEDURE provision_shard_slots(IN p_shard_index INT UNSIGNED)
BEGIN
    DECLARE v_slot INT UNSIGNED;
    DECLARE v_end INT UNSIGNED;

    IF p_shard_index >= 4 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'shard index must be between 0 and 3';
    END IF;

    SET v_slot = p_shard_index * 256;
    SET v_end = v_slot + 256;
    WHILE v_slot < v_end DO
        INSERT INTO slot_fences(slot_id, route_epoch, writable)
        VALUES (v_slot, 1, TRUE)
        ON DUPLICATE KEY UPDATE route_epoch = VALUES(route_epoch);
        SET v_slot = v_slot + 1;
    END WHILE;
END$$
DELIMITER ;

