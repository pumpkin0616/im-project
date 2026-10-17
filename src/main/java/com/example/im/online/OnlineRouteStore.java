package com.example.im.online;

import com.example.im.config.ImProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public final class OnlineRouteStore {
    private static final Logger log = LoggerFactory.getLogger(OnlineRouteStore.class);
    private static final DefaultRedisScript<Long> RENEW = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('PEXPIRE', KEYS[1], ARGV[2])
              redis.call('ZADD', KEYS[2], ARGV[3], ARGV[4])
              redis.call('PEXPIRE', KEYS[2], ARGV[2] * 2)
              return 1
            end
            return 0
            """, Long.class);
    private static final DefaultRedisScript<Long> REMOVE = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              redis.call('DEL', KEYS[1])
              redis.call('ZREM', KEYS[2], ARGV[2])
              return 1
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ImProperties.Online config;
    private final Counter failures;

    public OnlineRouteStore(StringRedisTemplate redis, ObjectMapper objectMapper,
                            ImProperties properties, MeterRegistry meters) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.config = properties.online();
        this.failures = meters.counter("im.online.redis.failures");
    }

    public OnlineRoute local(UUID userId, UUID deviceId, String connectionId, String bootId) {
        return new OnlineRoute(userId, deviceId, connectionId, config.gatewayId(), bootId, config.dispatchUrl());
    }

    public void register(OnlineRoute route) {
        if (!config.enabled()) return;
        safely("register", () -> {
            String json = encode(route);
            redis.opsForValue().set(routeKey(route.userId(), route.deviceId()), json, config.leaseTtl());
            redis.opsForZSet().add(userKey(route.userId()), route.deviceId().toString(), expiresAt());
            redis.expire(userKey(route.userId()), config.leaseTtl().multipliedBy(2));
        });
    }

    public void renew(OnlineRoute route) {
        if (!config.enabled()) return;
        safely("renew", () -> redis.execute(RENEW,
                List.of(routeKey(route.userId(), route.deviceId()), userKey(route.userId())),
                encode(route), Long.toString(config.leaseTtl().toMillis()),
                Double.toString(expiresAt()), route.deviceId().toString()));
    }

    public void unregister(OnlineRoute route) {
        if (!config.enabled()) return;
        safely("unregister", () -> redis.execute(REMOVE,
                List.of(routeKey(route.userId(), route.deviceId()), userKey(route.userId())),
                encode(route), route.deviceId().toString()));
    }

    public Map<UUID, List<OnlineRoute>> findByUsers(Collection<UUID> userIds) {
        Map<UUID, List<OnlineRoute>> result = new HashMap<>();
        if (!config.enabled() || userIds.isEmpty()) return result;
        try {
            double now = Instant.now().toEpochMilli();
            List<UUID> users = List.copyOf(userIds);
            List<Object> indexes = redis.executePipelined((RedisCallback<Object>) connection -> {
                for (UUID userId : users) connection.zSetCommands().zRangeByScore(
                        userKey(userId).getBytes(StandardCharsets.UTF_8), now, Double.POSITIVE_INFINITY);
                return null;
            });
            List<String> routeKeys = new ArrayList<>();
            for (int index = 0; index < users.size(); index++) {
                Object raw = indexes.get(index);
                if (!(raw instanceof Collection<?> devices)) continue;
                for (Object device : devices) routeKeys.add(routeKey(users.get(index), UUID.fromString(device.toString())));
            }
            if (routeKeys.isEmpty()) return result;
            List<String> values = redis.opsForValue().multiGet(routeKeys);
            if (values == null) return result;
            for (String value : values) {
                if (value == null) continue;
                OnlineRoute route = decode(value);
                result.computeIfAbsent(route.userId(), ignored -> new ArrayList<>()).add(route);
            }
        } catch (RuntimeException exception) {
            failures.increment();
            log.warn("Redis online route lookup failed: {}", exception.getMessage());
        }
        return result;
    }

    private void safely(String operation, Runnable action) {
        try { action.run(); }
        catch (RuntimeException exception) {
            failures.increment();
            log.warn("Redis online route {} failed: {}", operation, exception.getMessage());
        }
    }

    private String encode(OnlineRoute route) {
        try { return objectMapper.writeValueAsString(route); }
        catch (JacksonException exception) { throw new IllegalStateException("Cannot encode online route", exception); }
    }

    private OnlineRoute decode(String value) {
        try { return objectMapper.readValue(value, OnlineRoute.class); }
        catch (JacksonException exception) { throw new IllegalStateException("Cannot decode online route", exception); }
    }

    private double expiresAt() { return Instant.now().plus(config.leaseTtl()).toEpochMilli(); }
    private String routeKey(UUID userId, UUID deviceId) { return "im:online:route:" + userId + ":" + deviceId; }
    private String userKey(UUID userId) { return "im:online:user:" + userId; }
}
