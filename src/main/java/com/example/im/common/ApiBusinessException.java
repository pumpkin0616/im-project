package com.example.im.common;

public final class ApiBusinessException extends RuntimeException {
    private final String code;
    private final int status;
    private final boolean retryable;

    public ApiBusinessException(String code, String message, int status) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryable = false;
    }

    public String code() { return code; }
    public int status() { return status; }
    public boolean retryable() { return retryable; }
}
