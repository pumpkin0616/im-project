package com.example.im.message.api;

import com.example.im.message.domain.MessageRejectedException;
import com.example.im.common.ApiBusinessException;
import com.example.im.routing.RouteResolutionException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestControllerAdvice
public final class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private final Clock clock = Clock.systemUTC();

    @ExceptionHandler(ApiBusinessException.class)
    ResponseEntity<ApiError> business(ApiBusinessException exception) {
        return ResponseEntity.status(exception.status()).body(new ApiError(
                exception.code(), exception.getMessage(), exception.retryable(), clock.instant()));
    }

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

    @ExceptionHandler(RouteResolutionException.class)
    ResponseEntity<ApiError> route(RouteResolutionException exception) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new ApiError(
                exception.code(), exception.getMessage(), true, clock.instant()));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception) {
        log.error("Unhandled API failure", exception);
        return ResponseEntity.internalServerError().body(new ApiError(
                "INTERNAL_ERROR", "The request could not be completed", true, clock.instant()));
    }
}

