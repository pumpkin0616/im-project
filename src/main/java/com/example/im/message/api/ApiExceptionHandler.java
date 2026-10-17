package com.example.im.message.api;

import com.example.im.message.domain.MessageRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private final Clock clock = Clock.systemUTC();

    @ExceptionHandler(MessageRejectedException.class)
    ResponseEntity<ApiError> rejected(MessageRejectedException exception) {
        HttpStatus status = switch (exception.code()) {
            case "CONVERSATION_NOT_FOUND" -> HttpStatus.NOT_FOUND;
            case "ROUTE_MISMATCH", "ROUTE_STALE", "DEDUP_GENERATION_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE;
            case "IDEMPOTENCY_CONFLICT" -> HttpStatus.CONFLICT;
            default -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
        return ResponseEntity.status(status).body(new ApiError(
                exception.code(), exception.getMessage(), exception.retryable(), clock.instant()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException exception) {
        return ResponseEntity.badRequest().body(new ApiError(
                "INVALID_REQUEST", "Request validation failed", false, clock.instant()));
    }
}

