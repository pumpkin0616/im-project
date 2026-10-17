package com.example.im.message.domain;

public final class MessageRejectedException extends RuntimeException {
    private final String code;
    private final boolean retryable;

    public MessageRejectedException(String code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public String code() {
        return code;
    }

    public boolean retryable() {
        return retryable;
    }
}

