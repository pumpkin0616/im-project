CREATE USER IF NOT EXISTS 'im_app'@'%' IDENTIFIED BY 'im_app';
GRANT SELECT, INSERT, UPDATE, DELETE ON im_message.* TO 'im_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON im_control.* TO 'im_app'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON im_directory.* TO 'im_app'@'%';
GRANT EXECUTE ON PROCEDURE im_message.ensure_message_partitions TO 'im_app'@'%';
GRANT EXECUTE ON PROCEDURE im_message.provision_dedup_generation TO 'im_app'@'%';
FLUSH PRIVILEGES;

CALL im_message.provision_shard_slots(0);
