USE im_message;

DELIMITER $$
DROP PROCEDURE IF EXISTS ensure_message_partitions$$
CREATE PROCEDURE ensure_message_partitions(IN p_through DATE)
SQL SECURITY DEFINER
BEGIN
    DECLARE g INT DEFAULT 0;
    DECLARE d DATE DEFAULT UTC_DATE();
    DECLARE partition_name_value VARCHAR(16);
    DECLARE message_table VARCHAR(32);
    DECLARE lock_acquired INT DEFAULT 0;
    DECLARE EXIT HANDLER FOR SQLEXCEPTION
    BEGIN
        IF lock_acquired = 1 THEN DO RELEASE_LOCK('im_message_partition_maintenance'); END IF;
        RESIGNAL;
    END;
    SELECT GET_LOCK('im_message_partition_maintenance', 0) INTO lock_acquired;
    IF lock_acquired = 1 THEN
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
        DO RELEASE_LOCK('im_message_partition_maintenance');
    ELSE
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='partition maintenance is already running';
    END IF;
END$$
DELIMITER ;
