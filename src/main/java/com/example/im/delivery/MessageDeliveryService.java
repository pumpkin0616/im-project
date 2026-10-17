package com.example.im.delivery;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
final class MessageDeliveryService {
    private final DeliveryMessageRepository messages;
    private final RealtimeDeliveryRouter router;

    MessageDeliveryService(DeliveryMessageRepository messages, RealtimeDeliveryRouter router) {
        this.messages = messages;
        this.router = router;
    }

    void deliver(UUID conversationId, long sequence) {
        DeliveryMessage message = messages.load(conversationId, sequence);
        router.dispatch(message.recipients(), "MESSAGE", message.payload());
    }
}
