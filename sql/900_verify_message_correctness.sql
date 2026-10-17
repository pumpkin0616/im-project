-- Run on each physical message shard after a load test.
USE im_message;

-- A non-empty result means one client request produced more than one formal message.
SELECT conversation_id, sender_id, client_msg_id, COUNT(*) AS copies
FROM (
    SELECT conversation_id, sender_id, client_msg_id FROM messages_00
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_01
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_02
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_03
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_04
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_05
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_06
    UNION ALL SELECT conversation_id, sender_id, client_msg_id FROM messages_07
) AS all_messages
GROUP BY conversation_id, sender_id, client_msg_id
HAVING COUNT(*) > 1;

-- Formal messages must have one row per sequence in a conversation.
WITH all_messages AS (
    SELECT conversation_id, seq FROM messages_00
    UNION ALL SELECT conversation_id, seq FROM messages_01
    UNION ALL SELECT conversation_id, seq FROM messages_02
    UNION ALL SELECT conversation_id, seq FROM messages_03
    UNION ALL SELECT conversation_id, seq FROM messages_04
    UNION ALL SELECT conversation_id, seq FROM messages_05
    UNION ALL SELECT conversation_id, seq FROM messages_06
    UNION ALL SELECT conversation_id, seq FROM messages_07
), sequence_check AS (
    SELECT conversation_id, seq,
           LAG(seq) OVER (PARTITION BY conversation_id ORDER BY seq) AS previous_seq
    FROM all_messages
)
SELECT conversation_id, previous_seq, seq
FROM sequence_check
WHERE previous_seq IS NOT NULL AND seq <> previous_seq + 1;

-- Pending/leased rows and the oldest age reveal whether publishing is catching up.
SELECT table_group, status, COUNT(*) AS rows_count,
       TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(3)) / 1000 AS oldest_age_ms
FROM (
    SELECT 0 table_group, status, created_at FROM outbox_00 WHERE status IN (0, 1)
    UNION ALL SELECT 1, status, created_at FROM outbox_01 WHERE status IN (0, 1)
    UNION ALL SELECT 2, status, created_at FROM outbox_02 WHERE status IN (0, 1)
    UNION ALL SELECT 3, status, created_at FROM outbox_03 WHERE status IN (0, 1)
    UNION ALL SELECT 4, status, created_at FROM outbox_04 WHERE status IN (0, 1)
    UNION ALL SELECT 5, status, created_at FROM outbox_05 WHERE status IN (0, 1)
    UNION ALL SELECT 6, status, created_at FROM outbox_06 WHERE status IN (0, 1)
    UNION ALL SELECT 7, status, created_at FROM outbox_07 WHERE status IN (0, 1)
) AS pending_outbox
GROUP BY table_group, status
ORDER BY table_group, status;
