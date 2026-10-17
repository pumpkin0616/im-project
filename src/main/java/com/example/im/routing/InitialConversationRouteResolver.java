package com.example.im.routing;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Initial four-shard mapping. A control-plane backed resolver will replace this
 * once slot overrides and online migrations are implemented.
 */
@Component
public final class InitialConversationRouteResolver implements ConversationRouteResolver {
    private final SlotCalculator slotCalculator;

    public InitialConversationRouteResolver(SlotCalculator slotCalculator) {
        this.slotCalculator = slotCalculator;
    }

    @Override
    public ResolvedRoute resolve(UUID conversationId) {
        int slot = slotCalculator.slot(conversationId);
        String shardId = "shard-" + (slot / 256);
        int tableGroup = (slot % 256) / 32;
        return new ResolvedRoute(slot, shardId, tableGroup, 1);
    }
}

