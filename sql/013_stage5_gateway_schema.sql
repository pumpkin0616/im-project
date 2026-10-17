-- Stage 5 one-time WebSocket connection tickets. Safe to run repeatedly.
CREATE TABLE IF NOT EXISTS im_control.websocket_tickets (
    ticket_hash     BINARY(32) NOT NULL,
    id              BINARY(16) NOT NULL,
    user_id         BINARY(16) NOT NULL,
    session_id      BINARY(16) NOT NULL,
    device_id       BINARY(16) NOT NULL,
    expires_at      DATETIME(3) NOT NULL,
    used_at         DATETIME(3) NULL,
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (ticket_hash),
    UNIQUE KEY uk_websocket_tickets_id (id),
    KEY idx_websocket_tickets_expiry (expires_at),
    KEY idx_websocket_tickets_session (session_id, used_at)
) ENGINE=InnoDB;
