CREATE DATABASE IF NOT EXISTS im_control CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
USE im_control;

CREATE TABLE IF NOT EXISTS direct_conversations (
    direct_key       BINARY(32) NOT NULL,
    conversation_id  BINARY(16) NOT NULL,
    created_at       DATETIME(3) NOT NULL,
    PRIMARY KEY (direct_key),
    UNIQUE KEY uk_direct_conversations_id (conversation_id)
) ENGINE=InnoDB;
