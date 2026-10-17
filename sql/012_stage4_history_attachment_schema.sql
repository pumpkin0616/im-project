-- Stage 4 online-query indexes. Safe to run repeatedly against an existing local database.
-- Production applies each messages_* ALTER with online-DDL monitoring on every physical shard.

DELIMITER $$
DROP PROCEDURE IF EXISTS im_message.add_index_if_missing$$
CREATE PROCEDURE im_message.add_index_if_missing(
    IN p_schema VARCHAR(64), IN p_table VARCHAR(64), IN p_index VARCHAR(64), IN p_columns VARCHAR(512)
)
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema=p_schema AND table_name=p_table AND index_name=p_index
    ) THEN
        SET @index_ddl=CONCAT('ALTER TABLE `',p_schema,'`.`',p_table,'` ADD INDEX `',p_index,'` ',p_columns);
        PREPARE index_statement FROM @index_ddl;
        EXECUTE index_statement;
        DEALLOCATE PREPARE index_statement;
    END IF;
END$$
DELIMITER ;

CALL im_message.add_index_if_missing('im_message','messages_00','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_01','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_02','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_03','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_04','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_05','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_06','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_message','messages_07','idx_messages_conversation_seq','(`conversation_id`,`seq`)');
CALL im_message.add_index_if_missing('im_directory','user_conversations','idx_user_conversations_conversation','(`conversation_id`,`status`,`user_id`)');

DROP PROCEDURE im_message.add_index_if_missing;
