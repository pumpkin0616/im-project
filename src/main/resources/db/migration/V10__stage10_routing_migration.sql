-- Stage 10: control-plane routing, cache revision and migration orchestration.
-- Safe to run repeatedly on MySQL 8.4.
SET time_zone = '+00:00';

USE im_message;

CREATE TABLE IF NOT EXISTS conversation_fences (
    conversation_id BINARY(16) NOT NULL,
    route_epoch     BIGINT UNSIGNED NOT NULL,
    writable        BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (conversation_id)
) ENGINE=InnoDB;

-- DDL stays outside the application account. Provision every known idempotency
-- generation for all table groups before a route is allowed to move.
DELIMITER $$
DROP PROCEDURE IF EXISTS provision_existing_dedup_generations$$
CREATE PROCEDURE provision_existing_dedup_generations()
BEGIN
    DECLARE done INT DEFAULT FALSE;
    DECLARE v_generation BIGINT UNSIGNED;
    DECLARE generations CURSOR FOR SELECT generation FROM dedup_generations;
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET done = TRUE;

    OPEN generations;
    generation_loop: LOOP
        FETCH generations INTO v_generation;
        IF done THEN LEAVE generation_loop; END IF;
        CALL provision_dedup_generation(v_generation);
    END LOOP;
    CLOSE generations;
END$$
DELIMITER ;

CALL provision_existing_dedup_generations();
DROP PROCEDURE provision_existing_dedup_generations;

USE im_control;

CREATE TABLE IF NOT EXISTS routing_metadata (
    id              TINYINT UNSIGNED NOT NULL,
    route_version   BIGINT UNSIGNED NOT NULL DEFAULT 1,
    updated_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT ck_routing_metadata_singleton CHECK (id = 1)
) ENGINE=InnoDB;

INSERT INTO routing_metadata(id, route_version, updated_at)
VALUES (1, 1, UTC_TIMESTAMP(3))
ON DUPLICATE KEY UPDATE updated_at = updated_at;

DELIMITER $$
DROP PROCEDURE IF EXISTS provision_control_routes$$
CREATE PROCEDURE provision_control_routes()
BEGIN
    DECLARE v_slot INT UNSIGNED DEFAULT 0;
    DECLARE v_shard INT UNSIGNED;
    DECLARE v_group INT UNSIGNED;

    WHILE v_slot < 1024 DO
        SET v_shard = FLOOR(v_slot / 256);
        SET v_group = FLOOR(MOD(v_slot, 256) / 32);
        INSERT INTO slot_routes(slot_id, shard_id, table_group, route_epoch, state, updated_at)
        VALUES (v_slot, CONCAT('shard-', v_shard), v_group, 1, 1, UTC_TIMESTAMP(3))
        ON DUPLICATE KEY UPDATE slot_id = VALUES(slot_id);
        SET v_slot = v_slot + 1;
    END WHILE;
END$$
DELIMITER ;

CALL provision_control_routes();
DROP PROCEDURE provision_control_routes;

-- A scope can have only one unfinished migration. Generated columns let MySQL
-- enforce this while still retaining completed job history.
DELIMITER $$
DROP PROCEDURE IF EXISTS add_stage10_migration_columns$$
CREATE PROCEDURE add_stage10_migration_columns()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.COLUMNS
        WHERE TABLE_SCHEMA='im_control' AND TABLE_NAME='migration_jobs' AND COLUMN_NAME='started_at'
    ) THEN
        ALTER TABLE migration_jobs
            ADD COLUMN started_at DATETIME(3) NULL AFTER last_error,
            ADD COLUMN completed_at DATETIME(3) NULL AFTER started_at,
            ADD COLUMN owner_id VARCHAR(128) CHARACTER SET ascii NULL AFTER completed_at,
            ADD COLUMN lease_until DATETIME(3) NULL AFTER owner_id;
    END IF;
END$$
DELIMITER ;

CALL add_stage10_migration_columns();
DROP PROCEDURE add_stage10_migration_columns;

-- Route rows are authoritative; every change increments the singleton version.
-- Application instances poll this small row and invalidate their local caches.

