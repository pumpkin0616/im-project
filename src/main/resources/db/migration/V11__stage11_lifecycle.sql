-- Stage 11: archive verification, lifecycle audit and partition maintenance.
-- Run as a DDL account on every physical message shard.
SET time_zone = '+00:00';
USE im_message;

CREATE TABLE IF NOT EXISTS lifecycle_runs (
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

DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_archive_tables$$
CREATE PROCEDURE upgrade_archive_tables()
BEGIN
    DECLARE g INT DEFAULT 0;
    DECLARE table_name_value VARCHAR(64);
    DECLARE constraint_name_value VARCHAR(64);
    WHILE g < 8 DO
        SET table_name_value = CONCAT('archive_batches_', LPAD(g, 2, '0'));
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                       AND TABLE_NAME=table_name_value AND COLUMN_NAME='attempt_count') THEN
            SET @sql = CONCAT('ALTER TABLE ', table_name_value,
                ' ADD COLUMN attempt_count INT UNSIGNED NOT NULL DEFAULT 0 AFTER manifest_sha256,',
                ' ADD COLUMN last_error TEXT NULL AFTER attempt_count,',
                ' ADD COLUMN updated_at DATETIME(3) NULL AFTER created_at,',
                ' ADD COLUMN verified_at DATETIME(3) NULL AFTER updated_at');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
        SET @sql = CONCAT('UPDATE ', table_name_value, ' SET updated_at=created_at WHERE updated_at IS NULL');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        SET @sql = CONCAT('ALTER TABLE ', table_name_value, ' MODIFY updated_at DATETIME(3) NOT NULL');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        SELECT CONSTRAINT_NAME INTO constraint_name_value FROM information_schema.TABLE_CONSTRAINTS
         WHERE CONSTRAINT_SCHEMA=DATABASE() AND TABLE_NAME=table_name_value AND CONSTRAINT_TYPE='CHECK' LIMIT 1;
        IF constraint_name_value IS NOT NULL THEN
            SET @sql = CONCAT('ALTER TABLE ', table_name_value, ' DROP CHECK ', constraint_name_value);
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
        SET @sql = CONCAT('ALTER TABLE ', table_name_value, ' ADD CONSTRAINT ck_archive_state_',
            LPAD(g, 2, '0'), ' CHECK (state IN (1,2,3,4,5,6))');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

        SET table_name_value = CONCAT('archive_segments_', LPAD(g, 2, '0'));
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
                       AND TABLE_NAME=table_name_value AND COLUMN_NAME='object_sha256') THEN
            SET @sql = CONCAT('ALTER TABLE ', table_name_value,
                ' ADD COLUMN object_sha256 BINARY(32) NULL AFTER object_key,',
                ' ADD COLUMN byte_size BIGINT UNSIGNED NULL AFTER object_sha256');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET @sql = CONCAT('UPDATE ', table_name_value,
                ' SET object_sha256=UNHEX(REPEAT(''00'',32)),byte_size=0 WHERE object_sha256 IS NULL');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            SET @sql = CONCAT('ALTER TABLE ', table_name_value,
                ' MODIFY object_sha256 BINARY(32) NOT NULL, MODIFY byte_size BIGINT UNSIGNED NOT NULL');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END IF;
        SET g = g + 1;
    END WHILE;
END$$
DELIMITER ;

CALL upgrade_archive_tables();
DROP PROCEDURE upgrade_archive_tables;

DELIMITER $$
DROP PROCEDURE IF EXISTS ensure_message_partitions$$
CREATE PROCEDURE ensure_message_partitions(IN p_through DATE)
SQL SECURITY DEFINER
BEGIN
    DECLARE g INT DEFAULT 0;
    DECLARE d DATE DEFAULT UTC_DATE();
    DECLARE partition_name_value VARCHAR(16);
    DECLARE message_table VARCHAR(32);
    WHILE g < 8 DO
        SET d = UTC_DATE();
        SET message_table = CONCAT('messages_', LPAD(g, 2, '0'));
        WHILE d <= p_through DO
            SET partition_name_value = CONCAT('p', DATE_FORMAT(d, '%Y%m%d'));
            IF NOT EXISTS (SELECT 1 FROM information_schema.PARTITIONS WHERE TABLE_SCHEMA=DATABASE()
                           AND TABLE_NAME=message_table AND PARTITION_NAME=partition_name_value) THEN
                SET @sql = CONCAT('ALTER TABLE ', message_table,
                    ' REORGANIZE PARTITION p_future INTO (PARTITION ', partition_name_value,
                    ' VALUES LESS THAN (''', DATE_FORMAT(DATE_ADD(d, INTERVAL 1 DAY), '%Y-%m-%d'),
                    '''), PARTITION p_future VALUES LESS THAN (MAXVALUE))');
                PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
            END IF;
            SET d = DATE_ADD(d, INTERVAL 1 DAY);
        END WHILE;
        SET g = g + 1;
    END WHILE;
END$$
DELIMITER ;

