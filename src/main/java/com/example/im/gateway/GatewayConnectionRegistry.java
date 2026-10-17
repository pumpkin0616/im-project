package com.example.im.gateway;

import com.example.im.config.ImProperties;
import com.example.im.online.OnlineRoute;
import com.example.im.online.OnlineRouteStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Qualifier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public final class GatewayConnectionRegistry {
    private final UUID bootId = UUID.randomUUID();
    private final Map<DeviceKey, Channel> devices = new ConcurrentHashMap<>();
    private final Map<String, Channel> connections = new ConcurrentHashMap<>();
    private final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private final ObjectMapper objectMapper;
    private final OnlineRouteStore routes;
    private final ExecutorService routeExecutor;
    private final ImProperties.Gateway config;
    private final AtomicInteger active = new AtomicInteger();

    GatewayConnectionRegistry(ObjectMapper objectMapper, MeterRegistry meters, OnlineRouteStore routes,
                              @Qualifier("onlineRouteExecutor") ExecutorService routeExecutor,
                              ImProperties properties) {
        this.objectMapper = objectMapper;
        this.routes = routes;
        this.routeExecutor = routeExecutor;
        this.config = properties.gateway();
        meters.gauge("im.gateway.connections.active", active);
    }

    void register(Channel channel) {
        GatewayTicketService.Identity identity = channel.attr(GatewayAttributes.IDENTITY).get();
        String connectionId = channel.attr(GatewayAttributes.CONNECTION_ID).get();
        DeviceKey key = new DeviceKey(identity.userId(), identity.deviceId());
        channels.add(channel);
        connections.put(connectionId, channel);
        if (!Boolean.TRUE.equals(channel.attr(GatewayAttributes.REGISTERED).getAndSet(true))) {
            active.incrementAndGet();
        }
        Channel previous = devices.put(key, channel);
        OnlineRoute route = routes.local(identity.userId(), identity.deviceId(), connectionId, bootId.toString());
        channel.attr(GatewayAttributes.ONLINE_ROUTE).set(route);
        routeExecutor.execute(() -> routes.register(route));
        if (previous != null && previous != channel && previous.isActive()) {
            previous.writeAndFlush(new TextWebSocketFrame(json("RECONNECT", Map.of("reason", "CONNECTION_REPLACED"))));
            previous.writeAndFlush(new CloseWebSocketFrame(4001, "CONNECTION_REPLACED"))
                    .addListener(ignored -> previous.close());
        }
    }

    void unregister(Channel channel) {
        GatewayTicketService.Identity identity = channel.attr(GatewayAttributes.IDENTITY).get();
        String connectionId = channel.attr(GatewayAttributes.CONNECTION_ID).get();
        if (connectionId != null) connections.remove(connectionId, channel);
        boolean current = identity != null && devices.remove(new DeviceKey(identity.userId(), identity.deviceId()), channel);
        OnlineRoute route = channel.attr(GatewayAttributes.ONLINE_ROUTE).get();
        if (current && route != null) routeExecutor.execute(() -> routes.unregister(route));
        channels.remove(channel);
        if (Boolean.TRUE.equals(channel.attr(GatewayAttributes.REGISTERED).getAndSet(false))) {
            active.decrementAndGet();
        }
    }

    void closeAll() {
        channels.writeAndFlush(new CloseWebSocketFrame(1012, "SERVICE_RESTART"));
        channels.close().awaitUninterruptibly();
        devices.clear();
        connections.clear();
        active.set(0);
    }

    @Scheduled(fixedDelayString = "${im.online.renew-interval:30s}")
    void renewLeases() {
        devices.values().forEach(channel -> {
            OnlineRoute route = channel.attr(GatewayAttributes.ONLINE_ROUTE).get();
            if (route != null && channel.isActive()) routeExecutor.execute(() -> routes.renew(route));
        });
    }

    public int dispatch(List<String> connectionIds, String operation, Object data) {
        String encoded = json(operation, data);
        AtomicInteger accepted = new AtomicInteger();
        for (String connectionId : connectionIds) {
            Channel channel = connections.get(connectionId);
            if (channel == null || !channel.isActive()) continue;
            channel.eventLoop().execute(() -> {
                if (!channel.isActive()) return;
                int bytes = encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
                long pending = channel.isWritable()
                        ? Math.max(0, config.writeHighWatermarkBytes() - channel.bytesBeforeUnwritable())
                        : config.writeLowWatermarkBytes() + channel.bytesBeforeWritable();
                if (pending + bytes > config.maxPendingOutboundBytes()) {
                    channel.writeAndFlush(new CloseWebSocketFrame(4002, "OUTBOUND_BUFFER_LIMIT"))
                            .addListener(ignored -> channel.close());
                    return;
                }
                channel.writeAndFlush(new TextWebSocketFrame(encoded));
            });
            accepted.incrementAndGet();
        }
        return accepted.get();
    }

    public UUID bootId() { return bootId; }
    public int activeCount() { return active.get(); }

    private String json(String operation, Map<String, Object> data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("v", 1); value.put("op", operation); value.put("data", data);
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }

    private String json(String operation, Object data) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("v", 1); value.put("op", operation); value.put("data", data);
        try { return objectMapper.writeValueAsString(value); }
        catch (JacksonException exception) { throw new IllegalStateException(exception); }
    }

    private record DeviceKey(UUID userId, UUID deviceId) {}
}
