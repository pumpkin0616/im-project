package com.example.im.conversation;

import com.example.im.conversation.ConversationDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/conversations")
public final class ConversationController {
    private final ConversationService service;
    public ConversationController(ConversationService service) { this.service=service; }
    private UUID user(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    @PostMapping("/direct") @ResponseStatus(HttpStatus.CREATED)
    ConversationView direct(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateDirectRequest request) {
        return service.createDirect(user(jwt),request.peerUserId());
    }
    @PostMapping("/group") @ResponseStatus(HttpStatus.CREATED)
    ConversationView group(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateGroupRequest request) {
        return service.createGroup(user(jwt),request);
    }
    @GetMapping ConversationPage list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(defaultValue="50") int limit,
                                      @RequestParam(required=false) String cursor) {
        return service.list(user(jwt),Math.max(1,Math.min(limit,100)),cursor);
    }
    @GetMapping("/{id}") ConversationView get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return service.get(user(jwt),id,true);
    }
    @PatchMapping("/{id}") ConversationView update(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
                                                     @Valid @RequestBody UpdateConversationRequest request) {
        return service.updateTitle(user(jwt),id,request.title());
    }
    @PostMapping("/{id}/members") ConversationView addMembers(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
                                                               @Valid @RequestBody AddMembersRequest request) {
        return service.addMembers(user(jwt),id,request.userIds());
    }
    @DeleteMapping("/{id}/members/{memberId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeMember(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@PathVariable UUID memberId) {
        service.removeMember(user(jwt),id,memberId);
    }
    @PutMapping("/{id}/members/{memberId}/role") ConversationView role(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id,@PathVariable UUID memberId,@Valid @RequestBody UpdateRoleRequest request) {
        return service.updateRole(user(jwt),id,memberId,request.role());
    }
    @PostMapping("/{id}/leave") @ResponseStatus(HttpStatus.NO_CONTENT)
    void leave(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { service.leave(user(jwt),id); }
    @PostMapping("/{id}/dissolve") @ResponseStatus(HttpStatus.NO_CONTENT)
    void dissolve(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) { service.dissolve(user(jwt),id); }
}
