USE im_message;

DELIMITER $$
DROP PROCEDURE IF EXISTS provision_dedup_generation$$
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

GRANT EXECUTE ON PROCEDURE im_message.provision_dedup_generation TO 'im_app'@'%';
