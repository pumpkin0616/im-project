package com.example.im.routing.migration;

import com.example.im.common.ApiBusinessException;
import com.example.im.config.ImProperties;
import com.example.im.routing.ConversationRouteResolver;
import com.example.im.routing.ResolvedRoute;
import com.example.im.routing.RouteCacheControl;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

import static com.example.im.routing.migration.MigrationDtos.*;

@RestController
@RequestMapping("/internal/v1/routing")
final class RoutingAdminController {
    private final ConversationRouteResolver routes;
    private final RouteCacheControl cache;
    private final RouteMigrationService migrations;
    private final byte[] secret;

    RoutingAdminController(ConversationRouteResolver routes, RouteCacheControl cache,
                           RouteMigrationService migrations, ImProperties properties) {
        this.routes = routes;
        this.cache = cache;
        this.migrations = migrations;
        this.secret = properties.routing().adminSecret().getBytes(StandardCharsets.UTF_8);
    }

    @GetMapping("/routes/{conversationId}")
    RouteView route(@RequestHeader(value = "X-IM-Routing-Secret", required = false) String provided,
                    @PathVariable UUID conversationId) {
        authenticate(provided);
        ResolvedRoute route = routes.resolve(conversationId);
        return new RouteView(conversationId, route.slotId(), route.shardId(), route.tableGroup(),
                route.routeEpoch(), route.state().name(), route.scope().name());
    }

    @GetMapping("/cache")
    CacheView cache(@RequestHeader(value = "X-IM-Routing-Secret", required = false) String provided) {
        authenticate(provided);
        return new CacheView(cache.cachedRouteCount(), cache.observedRouteVersion());
    }

    @PostMapping("/cache/invalidate")
    CacheView invalidate(@RequestHeader(value = "X-IM-Routing-Secret", required = false) String provided) {
        authenticate(provided);
        cache.invalidateAll();
        return new CacheView(cache.cachedRouteCount(), cache.observedRouteVersion());
    }

    @PostMapping("/migrations")
    @ResponseStatus(HttpStatus.CREATED)
    MigrationView migrate(@RequestHeader(value = "X-IM-Routing-Secret", required = false) String provided,
                          @Valid @RequestBody StartMigrationRequest request) {
        authenticate(provided);
        return migrations.start(request);
    }

    @GetMapping("/migrations/{migrationId}")
    MigrationView migration(@RequestHeader(value = "X-IM-Routing-Secret", required = false) String provided,
                            @PathVariable UUID migrationId) {
        authenticate(provided);
        return migrations.get(migrationId);
    }

    private void authenticate(String provided) {
        if (provided == null) {
            throw new ApiBusinessException("ROUTING_ADMIN_UNAUTHORIZED", "Routing admin secret is invalid", 401);
        }
        byte[] candidate = provided.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(secret, candidate)) {
            throw new ApiBusinessException("ROUTING_ADMIN_UNAUTHORIZED", "Routing admin secret is invalid", 401);
        }
    }
}
