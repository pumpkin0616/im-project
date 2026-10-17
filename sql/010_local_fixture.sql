-- Deterministic development fixture routed to shard-0, table group 00, slot 31.
USE im_control;

INSERT INTO users(id, account, password_hash, nickname, status, auth_version, created_at, updated_at)
VALUES (
    UUID_TO_BIN('0199f120-0000-7000-8000-000000000001'),
    'local-user',
    '$2y$12$dFv/jLaSouQIwk.586t9Mugoj7sFGH3wF3GHDFqVI9fZ47FIBQvpK',
    'Local User', 1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE nickname = VALUES(nickname), status = 1, updated_at = VALUES(updated_at);

USE im_message;

SET @conversation_id = UUID_TO_BIN('bd258e60-98b5-3334-9859-e0112b4562e9');
SET @user_id = UUID_TO_BIN('0199f120-0000-7000-8000-000000000001');

INSERT INTO conversations_00(
    id, slot_id, route_epoch, write_state, type, direct_key, title, owner_id,
    status, delivery_mode, last_seq, min_available_seq, member_version,
    created_at, updated_at
) VALUES (
    @conversation_id, 31, 1, 1, 2, NULL, 'Local integration conversation', @user_id,
    1, 1, 0, 1, 1, UTC_TIMESTAMP(3), UTC_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE updated_at = VALUES(updated_at);

INSERT INTO members_00(
    conversation_id, user_id, slot_id, role, status, membership_version,
    joined_seq, last_read_seq, joined_at
) VALUES (
    @conversation_id, @user_id, 31, 3, 1, 1, 0, 0, UTC_TIMESTAMP(3)
)
ON DUPLICATE KEY UPDATE status = 1, left_at = NULL;
