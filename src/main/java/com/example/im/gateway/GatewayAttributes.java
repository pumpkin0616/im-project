package com.example.im.gateway;

import com.example.im.online.OnlineRoute;
import io.netty.util.AttributeKey;

final class GatewayAttributes {
    private GatewayAttributes() {}

    static final AttributeKey<GatewayTicketService.Identity> IDENTITY = AttributeKey.valueOf("gateway.identity");
    static final AttributeKey<String> CONNECTION_ID = AttributeKey.valueOf("gateway.connectionId");
    static final AttributeKey<Boolean> REGISTERED = AttributeKey.valueOf("gateway.registered");
    static final AttributeKey<OnlineRoute> ONLINE_ROUTE = AttributeKey.valueOf("gateway.onlineRoute");
}
