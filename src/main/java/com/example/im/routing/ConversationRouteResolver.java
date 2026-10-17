package com.example.im.routing;

import java.util.UUID;

public interface ConversationRouteResolver {
    ResolvedRoute resolve(UUID conversationId);
}

