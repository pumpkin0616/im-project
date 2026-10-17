-- Stage 9: message recall. Safe to run on existing and newly initialized MySQL 8.4 databases.
USE im_message;
SET time_zone = '+00:00';

DELIMITER //
CREATE PROCEDURE add_recall_columns(IN p_table_name VARCHAR(64))
BEGIN
  DECLARE column_count INT DEFAULT 0;
  SELECT COUNT(*) INTO column_count
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = p_table_name AND COLUMN_NAME = 'status';
  IF column_count = 0 THEN
    SET @ddl = CONCAT(
      'ALTER TABLE ', p_table_name,
      ' ADD COLUMN status TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT ''1 normal, 2 recalled'' AFTER type,',
      ' ADD COLUMN recalled_by BINARY(16) NULL AFTER content,',
      ' ADD COLUMN recalled_at DATETIME(3) NULL AFTER recalled_by,',
      ' ADD KEY idx_messages_recalled_at (recalled_at),',
      ' ADD CONSTRAINT ck_', p_table_name, '_recall_status CHECK (status IN (1, 2))'
    );
    PREPARE statement FROM @ddl;
    EXECUTE statement;
    DEALLOCATE PREPARE statement;
  END IF;
END//
DELIMITER ;

CALL add_recall_columns('messages_00');
CALL add_recall_columns('messages_01');
CALL add_recall_columns('messages_02');
CALL add_recall_columns('messages_03');
CALL add_recall_columns('messages_04');
CALL add_recall_columns('messages_05');
CALL add_recall_columns('messages_06');
CALL add_recall_columns('messages_07');
DROP PROCEDURE add_recall_columns;
