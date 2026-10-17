package com.example.im.message.api;

import java.time.Instant;

public record ApiError(String code, String message, boolean retryable, Instant timestamp) {
}

