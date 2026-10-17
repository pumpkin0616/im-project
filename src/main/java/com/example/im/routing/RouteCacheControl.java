package com.example.im.routing;

import java.util.UUID;

public interface RouteCacheControl {
    void invalidate(UUID conversationId);
    void invalidateAll();
    long cachedRouteCount();
    long observedRouteVersion();
}
