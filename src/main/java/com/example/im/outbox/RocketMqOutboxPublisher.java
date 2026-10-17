package com.example.im.outbox;

import com.example.im.config.ImProperties;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.message.Message;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
@ConditionalOnProperty(prefix = "im.rocketmq", name = "enabled", havingValue = "true")
final class RocketMqOutboxPublisher implements OutboxEventPublisher {
    private final ClientServiceProvider provider;
    private final Producer producer;
    private final ObjectMapper objectMapper;
    private final String topic;

    RocketMqOutboxPublisher(ClientServiceProvider provider, Producer producer,
                            ObjectMapper objectMapper, ImProperties properties) {
        this.provider = provider;
        this.producer = producer;
        this.objectMapper = objectMapper;
        this.topic = properties.rocketmq().messageTopic();
    }

    @Override
    public CompletableFuture<Void> publish(OutboxEvent event) {
        Message message = provider.newMessageBuilder()
                .setTopic(topic)
                .setTag(event.eventType())
                .setKeys(event.eventId().toString())
                .setMessageGroup(event.conversationId().toString())
                .setBody(serialize(event))
                .build();
        return producer.sendAsync(message).thenApply(receipt -> null);
    }

    private byte[] serialize(OutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", event.eventId());
        envelope.put("eventType", event.eventType());
        envelope.put("schemaVersion", event.schemaVersion());
        envelope.put("slotId", event.slotId());
        envelope.put("conversationId", event.conversationId());
        envelope.put("messageDay", event.messageDay());
        envelope.put("messageSequence", event.messageSequence());
        envelope.put("payload", event.payload());
        try {
            return objectMapper.writeValueAsBytes(envelope);
        } catch (JacksonException exception) {
            throw new OutboxPublishException("Cannot serialize outbox event " + event.eventId(), exception);
        }
    }
}
