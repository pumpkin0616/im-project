-- IM control-plane schema for MySQL 8.4 LTS.
-- All application timestamps are UTC and supplied explicitly by the service.

CREATE DATABASE IF NOT EXISTS im_control
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE im_control;

CREATE TABLE users (
    id              BINARY(16) NOT NULL,
    account         VARCHAR(128) COLLATE utf8mb4_bin NOT NULL,
    password_hash   VARCHAR(255) CHARACTER SET ascii NOT NULL,
    nickname        VARCHAR(64) NOT NULL,
    avatar_key      VARCHAR(512) CHARACTER SET ascii NULL,
    status          TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 active, 2 banned, 3 deleted',
    auth_version    BIGINT UNSIGNED NOT NULL DEFAULT 1,
    created_at      DATETIME(3) NOT NULL,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_account (account),
    CONSTRAINT ck_users_status CHECK (status IN (1, 2, 3))
) ENGINE=InnoDB;

CREATE TABLE auth_sessions (
    id                  BINARY(16) NOT NULL,
    user_id             BINARY(16) NOT NULL,
    device_id           BINARY(16) NOT NULL,
    refresh_token_hash  BINARY(32) NOT NULL,
    expires_at          DATETIME(3) NOT NULL,
    revoked_at          DATETIME(3) NULL,
    created_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_sessions_refresh (refresh_token_hash),
    KEY idx_auth_sessions_user (user_id, revoked_at),
    KEY idx_auth_sessions_expiry (expires_at)
) ENGINE=InnoDB;

CREATE TABLE friend_requests (
    id              BINARY(16) NOT NULL,
    requester_id    BINARY(16) NOT NULL,
    recipient_id    BINARY(16) NOT NULL,
    status          TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '0 pending, 1 accepted, 2 rejected, 3 cancelled',
    note            VARCHAR(256) NULL,
    created_at      DATETIME(3) NOT NULL,
    handled_at      DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_friend_requests_recipient (recipient_id, status, created_at),
    KEY idx_friend_requests_pair (requester_id, recipient_id, status),
    CONSTRAINT ck_friend_requests_self CHECK (requester_id <> recipient_id),
    CONSTRAINT ck_friend_requests_status CHECK (status IN (0, 1, 2, 3))
) ENGINE=InnoDB;

CREATE TABLE friendships (
    user_id         BINARY(16) NOT NULL,
    friend_id       BINARY(16) NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, friend_id),
    CONSTRAINT ck_friendships_self CHECK (user_id <> friend_id)
) ENGINE=InnoDB;

CREATE TABLE user_blocks (
    user_id         BINARY(16) NOT NULL,
    blocked_user_id BINARY(16) NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, blocked_user_id),
    CONSTRAINT ck_user_blocks_self CHECK (user_id <> blocked_user_id)
) ENGINE=InnoDB;

CREATE TABLE slot_routes (
    slot_id         SMALLINT UNSIGNED NOT NULL,
    shard_id        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    table_group     TINYINT UNSIGNED NOT NULL,
    route_epoch     BIGINT UNSIGNED NOT NULL DEFAULT 1,
    state           TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 active, 2 migrating, 3 disabled',
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (slot_id),
    CONSTRAINT ck_slot_routes_slot CHECK (slot_id < 1024),
    CONSTRAINT ck_slot_routes_group CHECK (table_group < 8),
    CONSTRAINT ck_slot_routes_state CHECK (state IN (1, 2, 3))
) ENGINE=InnoDB;

CREATE TABLE conversation_routes (
    conversation_id BINARY(16) NOT NULL,
    shard_id        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    table_group     TINYINT UNSIGNED NOT NULL,
    route_epoch     BIGINT UNSIGNED NOT NULL,
    state           TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 active, 2 migrating, 3 disabled',
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (conversation_id),
    CONSTRAINT ck_conversation_routes_group CHECK (table_group < 8),
    CONSTRAINT ck_conversation_routes_state CHECK (state IN (1, 2, 3))
) ENGINE=InnoDB;

CREATE TABLE direct_conversations (
    direct_key       BINARY(32) NOT NULL,
    conversation_id  BINARY(16) NOT NULL,
    created_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (direct_key),
    UNIQUE KEY uk_direct_conversations_id (conversation_id)
) ENGINE=InnoDB;

CREATE TABLE migration_jobs (
    id                  BINARY(16) NOT NULL,
    scope_type          TINYINT UNSIGNED NOT NULL COMMENT '1 slot, 2 conversation',
    slot_id             SMALLINT UNSIGNED NOT NULL,
    conversation_id     BINARY(16) NULL,
    source_shard        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    source_table_group  TINYINT UNSIGNED NOT NULL,
    target_shard        VARCHAR(64) CHARACTER SET ascii NOT NULL,
    target_table_group  TINYINT UNSIGNED NOT NULL,
    source_epoch        BIGINT UNSIGNED NOT NULL,
    target_epoch        BIGINT UNSIGNED NOT NULL,
    state               VARCHAR(32) CHARACTER SET ascii NOT NULL,
    checkpoint          JSON NULL,
    last_error          TEXT NULL,
    created_at          DATETIME(3) NOT NULL,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_migration_jobs_state (state, updated_at),
    CONSTRAINT ck_migration_scope CHECK (scope_type IN (1, 2)),
    CONSTRAINT ck_migration_slot CHECK (slot_id < 1024),
    CONSTRAINT ck_migration_source_group CHECK (source_table_group < 8),
    CONSTRAINT ck_migration_target_group CHECK (target_table_group < 8)
) ENGINE=InnoDB;

CREATE TABLE message_reports (
    id              BINARY(16) NOT NULL,
    reporter_id     BINARY(16) NOT NULL,
    conversation_id BINARY(16) NOT NULL,
    message_seq     BIGINT UNSIGNED NOT NULL,
    reason          VARCHAR(512) NOT NULL,
    status          TINYINT UNSIGNED NOT NULL DEFAULT 0,
    created_at      DATETIME(3) NOT NULL,
    handled_at      DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_message_reports_once (reporter_id, conversation_id, message_seq),
    KEY idx_message_reports_status (status, created_at)
) ENGINE=InnoDB;

CREATE TABLE audit_logs (
    id              BINARY(16) NOT NULL,
    actor_id        BINARY(16) NULL,
    action          VARCHAR(64) CHARACTER SET ascii NOT NULL,
    target_type     VARCHAR(32) CHARACTER SET ascii NOT NULL,
    target_id       VARCHAR(128) CHARACTER SET ascii NOT NULL,
    detail          JSON NOT NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_logs_created (created_at)
) ENGINE=InnoDB;

CREATE DATABASE IF NOT EXISTS im_directory
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE im_directory;

CREATE TABLE user_conversations (
    user_id             BINARY(16) NOT NULL,
    conversation_id     BINARY(16) NOT NULL,
    membership_version  BIGINT UNSIGNED NOT NULL,
    status              TINYINT UNSIGNED NOT NULL COMMENT '1 active, 2 left, 3 dissolved',
    last_activity_at    DATETIME(3) NOT NULL,
    last_known_seq      BIGINT UNSIGNED NOT NULL DEFAULT 0,
    updated_at          DATETIME(3) NOT NULL,
    PRIMARY KEY (user_id, conversation_id),
    KEY idx_user_conversations_activity (user_id, status, last_activity_at, conversation_id),
    KEY idx_user_conversations_conversation (conversation_id, status, user_id),
    CONSTRAINT ck_user_conversations_status CHECK (status IN (1, 2, 3))
) ENGINE=InnoDB;

