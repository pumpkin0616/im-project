package com.example.im.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ws/tickets")
public final class GatewayTicketController {
    private final GatewayTicketService service;

    public GatewayTicketController(GatewayTicketService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    GatewayTicketService.TicketResponse issue(@AuthenticationPrincipal Jwt jwt) {
        return service.issue(UUID.fromString(jwt.getSubject()),
                UUID.fromString(jwt.getClaimAsString("sid")),
                UUID.fromString(jwt.getClaimAsString("did")));
    }
}
