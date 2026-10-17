package com.example.im.gateway;

import com.example.im.config.ImProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

@RestController
@RequestMapping("/internal/v1/gateway")
public final class GatewayDispatchController {
    private final GatewayConnectionRegistry connections;
    private final byte[] secret;

    public GatewayDispatchController(GatewayConnectionRegistry connections, ImProperties properties) {
        this.connections = connections;
        this.secret = properties.online().dispatchSecret().getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/dispatch")
    DispatchResponse dispatch(@RequestHeader(value = "X-IM-Dispatch-Secret", required = false) String candidate,
                              @RequestBody DispatchRequest request) {
        byte[] supplied = candidate == null ? new byte[0] : candidate.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(secret, supplied)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        if (request.connectionIds() == null || request.connectionIds().isEmpty()
                || request.connectionIds().size() > 1000 || request.operation() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dispatch request");
        }
        return new DispatchResponse(connections.dispatch(request.connectionIds(), request.operation(), request.data()));
    }

    public record DispatchRequest(List<String> connectionIds, String operation, JsonNode data) {}
    public record DispatchResponse(int accepted) {}
}
