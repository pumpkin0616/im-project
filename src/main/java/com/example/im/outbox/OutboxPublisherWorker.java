package com.example.im.outbox;

import com.example.im.config.ImProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Component
@ConditionalOnProperty(prefix = "im.rocketmq", name = "enabled", havingValue = "true")
final class OutboxPublisherWorker {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherWorker.class);

    private final JdbcOutboxRepository repository;
    private final OutboxEventPublisher publisher;
    private final ImProperties properties;
    private final Executor completionExecutor;
    private final UUID leaseOwner = UUID.randomUUID();
    private final Semaphore inFlight;

    OutboxPublisherWorker(JdbcOutboxRepository repository, OutboxEventPublisher publisher,
                          ImProperties properties,
                          @Qualifier("outboxCompletionExecutor") Executor completionExecutor,
                          MeterRegistry meters) {
        this.repository = repository;
        this.publisher = publisher;
        this.properties = properties;
        this.completionExecutor = completionExecutor;
        this.inFlight = new Semaphore(properties.rocketmq().outboxMaxInFlight());
        int maximum = properties.rocketmq().outboxMaxInFlight();
        Gauge.builder("im.outbox.inflight", inFlight, value -> maximum - value.availablePermits())
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${im.rocketmq.outbox-poll-interval-ms:100}")
    void publishAvailableEvents() {
        ImProperties.RocketMq config = properties.rocketmq();
        for (int group = 0; group < properties.routing().tableGroupCount(); group++) {
            int capacity = Math.min(config.outboxBatchSize(), inFlight.availablePermits());
            if (capacity == 0) {
                return;
            }
            List<OutboxEvent> events = repository.claim(
                    group, capacity, leaseOwner, config.outboxLease());
            if (!inFlight.tryAcquire(events.size())) {
                throw new IllegalStateException("Outbox in-flight capacity changed unexpectedly");
            }
            for (OutboxEvent event : events) {
                CompletableFuture<Void> completion;
                try {
                    completion = publisher.publish(event);
                } catch (RuntimeException exception) {
                    completion = CompletableFuture.failedFuture(exception);
                }
                completion.whenCompleteAsync(
                        (ignored, failure) -> complete(event, failure, config), completionExecutor);
            }
        }
    }

    private void complete(OutboxEvent event, Throwable failure, ImProperties.RocketMq config) {
        try {
            if (failure == null) {
                if (!repository.markPublished(event, leaseOwner)) {
                    log.warn("Outbox lease was lost after publishing eventId={}", event.eventId());
                }
                return;
            }
            boolean released = repository.releaseAfterFailure(
                    event, leaseOwner, config.outboxMaxAttempts());
            log.warn("Outbox publish failed eventId={}, attempts={}, leaseReleased={}",
                    event.eventId(), event.attempts(), released, failure);
        } catch (RuntimeException databaseFailure) {
            log.error("Cannot persist outbox completion eventId={}; lease recovery will retry it",
                    event.eventId(), databaseFailure);
        } finally {
            inFlight.release();
        }
    }
}
