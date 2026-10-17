package com.example.im.gateway;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
final class GatewayConnectionRegistry {
    private final UUID bootId = UUID.randomUUID();
    private final Map<DeviceKey, Channel> devices = new ConcurrentHashMap<>();
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ObjectMapper objectMapper;
    private final AtomicInteger active = new AtomicInteger();

    GatewayConnectionRegistry(ObjectMapper objectMapper, MeterRegistry meters) {
        this.objectMapper = objectMapper;
        meters.gauge("im.gateway.connections.active", active);
    }

    void register(Channel channel) {
        GatewayTicketService.Identity identity = channel.attr(GatewayAttributes.IDENTITY).get();
        DeviceKey key = new DeviceKey(identity.userId(), identity.deviceId());
        channels.add(channel);
        if (!Boolean.TRUE.equals(channel.attr(GatewayAttributes.REGISTERED).getAndSet(true))) {
            active.incrementAndGet();
        }
        Channel previous = devices.put(key, channel);
        if (previous != null && previous != channel && previous.isActive()) {
            previous.writeAndFlush(new TextWebSocketFrame(json("RECONNECT", Map.of("reason", "CONNECTION_REPLACED"))));
            previous.writeAndFlush(new CloseWebSocketFrame(4001, "CONNECTION_REPLACED"))
                    .addListener(ignored -> previous.close());
        }
    }

    void unregister(Channel channel) {
        GatewayTicketService.Identity identity = channel.attr(GatewayAttributes.IDENTITY).get();
        if (identity != null) devices.remove(new DeviceKey(identity.userId(), identity.deviceId()), channel);
        channels.remove(channel);
        if (Boolean.TRUE.equals(channel.attr(GatewayAttributes.REGISTERED).getAndSet(false))) {
            active.decrementAndGet();
        }
    }

    void closeAll() {
        channels.writeAndFlush(new CloseWebSocketFrame(1012, "SERVICE_RESTART"));
        channels.close().awaitUninterruptibly();
        devices.clear();
        active.set(0);
    }

    UUID bootId() { return bootId; }
    int activeCount() { return active.get(); }

    private String json(String operation, Map<String, Object> data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("v", 1); value.put("op", operation); value.put("data", data);
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }

    private record DeviceKey(UUID userId, UUID deviceId) {}
}
