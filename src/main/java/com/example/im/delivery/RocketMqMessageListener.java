package com.example.im.delivery;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.MessageListener;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.ByteBuffer;
import java.util.UUID;

@Component
public final class RocketMqMessageListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RocketMqMessageListener.class);
    private final ObjectMapper objectMapper;
    private final MessageDeliveryService delivery;
    private final Counter success;
    private final Counter failures;

    public RocketMqMessageListener(ObjectMapper objectMapper, MessageDeliveryService delivery, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        this.delivery = delivery;
        this.success = meters.counter("im.delivery.events.success");
        this.failures = meters.counter("im.delivery.events.failure");
    }

    @Override
    public ConsumeResult consume(MessageView message) {
        try {
            ByteBuffer body = message.getBody().duplicate();
            byte[] bytes = new byte[body.remaining()];
            body.get(bytes);
            JsonNode event = objectMapper.readTree(bytes);
            if (!"MESSAGE_CREATED".equals(event.path("eventType").asString())) return ConsumeResult.SUCCESS;
            UUID conversationId = UUID.fromString(event.path("conversationId").asString());
            long sequence = event.path("messageSequence").asLong();
            if (sequence < 1) throw new IllegalArgumentException("messageSequence is missing");
            delivery.deliver(conversationId, sequence);
            success.increment();
            return ConsumeResult.SUCCESS;
        } catch (Exception exception) {
            failures.increment();
            log.warn("Realtime delivery failed for RocketMQ message {} attempt {}: {}",
                    message.getMessageId(), message.getDeliveryAttempt(), exception.getMessage());
            return ConsumeResult.FAILURE;
        }
    }
}
