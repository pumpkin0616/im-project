package com.example.im.routing;

public record ResolvedRoute(
        int slotId,
        String shardId,
        int tableGroup,
        long routeEpoch
) {
}

