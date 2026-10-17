package com.example.im.outbox;

import java.util.concurrent.CompletableFuture;

public interface OutboxEventPublisher {
    CompletableFuture<Void> publish(OutboxEvent event);
}
