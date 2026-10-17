package com.example.im.routing;

public record ResolvedRoute(
        int slotId,
        String shardId,
        int tableGroup,
        long routeEpoch,
        RouteState state,
        RouteScope scope
) {
    public ResolvedRoute(int slotId, String shardId, int tableGroup, long routeEpoch) {
        this(slotId, shardId, tableGroup, routeEpoch, RouteState.ACTIVE, RouteScope.SLOT);
    }

    public ResolvedRoute(int slotId, String shardId, int tableGroup, long routeEpoch, RouteState state) {
        this(slotId, shardId, tableGroup, routeEpoch, state, RouteScope.SLOT);
    }
}

