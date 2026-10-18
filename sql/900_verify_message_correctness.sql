-- Run on each physical message shard after a load test.
USE im_message;

-- A non-empty result means one client request produced more than one formal message.
SELECT BIN_TO_UUID(conversation_id) AS conversation_id,
       BIN_TO_UUID(sender_id) AS sender_id,
       BIN_TO_UUID(client_msg_id) AS client_msg_id,
       COUNT(*) AS copies
FROM (
    SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_00 m JOIN conversations_00 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_01 m JOIN conversations_01 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_02 m JOIN conversations_02 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_03 m JOIN conversations_03 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_04 m JOIN conversations_04 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_05 m JOIN conversations_05 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_06 m JOIN conversations_06 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.sender_id, m.client_msg_id FROM messages_07 m JOIN conversations_07 c ON c.id=m.conversation_id WHERE c.write_state<>3
) AS all_messages
GROUP BY conversation_id, sender_id, client_msg_id
HAVING COUNT(*) > 1;

-- Formal messages must have one row per sequence in a conversation.
WITH all_messages AS (
    SELECT m.conversation_id, m.seq FROM messages_00 m JOIN conversations_00 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_01 m JOIN conversations_01 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_02 m JOIN conversations_02 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_03 m JOIN conversations_03 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_04 m JOIN conversations_04 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_05 m JOIN conversations_05 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_06 m JOIN conversations_06 c ON c.id=m.conversation_id WHERE c.write_state<>3
    UNION ALL SELECT m.conversation_id, m.seq FROM messages_07 m JOIN conversations_07 c ON c.id=m.conversation_id WHERE c.write_state<>3
), sequence_check AS (
    SELECT conversation_id, seq,
           LAG(seq) OVER (PARTITION BY conversation_id ORDER BY seq) AS previous_seq
    FROM all_messages
)
SELECT BIN_TO_UUID(conversation_id) AS conversation_id, previous_seq, seq
FROM sequence_check
WHERE previous_seq IS NOT NULL AND seq <> previous_seq + 1;

-- Pending/leased rows and the oldest age reveal whether publishing is catching up.
SELECT table_group, status, COUNT(*) AS rows_count,
       TIMESTAMPDIFF(MICROSECOND, MIN(created_at), UTC_TIMESTAMP(3)) / 1000 AS oldest_age_ms
FROM (
    SELECT 0 table_group, o.status, o.created_at FROM outbox_00 o JOIN conversations_00 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 1, o.status, o.created_at FROM outbox_01 o JOIN conversations_01 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 2, o.status, o.created_at FROM outbox_02 o JOIN conversations_02 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 3, o.status, o.created_at FROM outbox_03 o JOIN conversations_03 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 4, o.status, o.created_at FROM outbox_04 o JOIN conversations_04 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 5, o.status, o.created_at FROM outbox_05 o JOIN conversations_05 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 6, o.status, o.created_at FROM outbox_06 o JOIN conversations_06 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
    UNION ALL SELECT 7, o.status, o.created_at FROM outbox_07 o JOIN conversations_07 c ON c.id=o.conversation_id WHERE c.write_state<>3 AND o.status IN (0, 1)
) AS pending_outbox
GROUP BY table_group, status
ORDER BY table_group, status;
