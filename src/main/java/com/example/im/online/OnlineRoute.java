package com.example.im.online;

import java.util.UUID;

public record OnlineRoute(UUID userId, UUID deviceId, String connectionId,
                          String gatewayId, String gatewayBootId, String dispatchUrl) {
}
