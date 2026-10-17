package com.example.im.outbox;

final class OutboxPublishException extends RuntimeException {
    OutboxPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
