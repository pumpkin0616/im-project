package com.example.im.delivery;

import com.example.im.config.ImProperties;
import com.example.im.gateway.GatewayConnectionRegistry;
import com.example.im.online.OnlineRoute;
import com.example.im.online.OnlineRouteStore;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public final class RealtimeDeliveryRouter {
    private final OnlineRouteStore routes;
    private final GatewayConnectionRegistry local;
    private final ImProperties.Online config;
    private final RestClient http = RestClient.builder().build();
    private final Counter dispatched;

    public RealtimeDeliveryRouter(OnlineRouteStore routes, GatewayConnectionRegistry local,
                                  ImProperties properties, MeterRegistry meters) {
        this.routes = routes;
        this.local = local;
        this.config = properties.online();
        this.dispatched = meters.counter("im.delivery.connections.dispatched");
    }

    public int dispatch(Collection<UUID> users, String operation, Object data) {
        Map<UUID, List<OnlineRoute>> found = routes.findByUsers(users);
        Map<GatewayTarget, List<String>> grouped = new LinkedHashMap<>();
        found.values().stream().flatMap(List::stream).forEach(route -> grouped
                .computeIfAbsent(new GatewayTarget(route.gatewayId(), route.dispatchUrl()), ignored -> new ArrayList<>())
                .add(route.connectionId()));
        int total = 0;
        for (Map.Entry<GatewayTarget, List<String>> entry : grouped.entrySet()) {
            int accepted;
            if (entry.getKey().gatewayId().equals(config.gatewayId())) {
                accepted = local.dispatch(entry.getValue(), operation, data);
            } else {
                DispatchResponse response = http.post().uri(entry.getKey().url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-IM-Dispatch-Secret", config.dispatchSecret())
                        .body(new DispatchRequest(entry.getValue(), operation, data))
                        .retrieve().body(DispatchResponse.class);
                accepted = response == null ? 0 : response.accepted();
            }
            total += accepted;
        }
        dispatched.increment(total);
        return total;
    }

    private record GatewayTarget(String gatewayId, String url) {}
    private record DispatchRequest(List<String> connectionIds, String operation, Object data) {}
    private record DispatchResponse(int accepted) {}
}
