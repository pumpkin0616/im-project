package com.example.im.friend;

import com.example.im.friend.FriendDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public final class FriendController {
    private final FriendService service;
    public FriendController(FriendService service){this.service=service;}
    private UUID user(Jwt jwt){return UUID.fromString(jwt.getSubject());}

    @PostMapping("/friend-requests") @ResponseStatus(HttpStatus.CREATED)
    RequestView create(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody CreateRequest request){return service.create(user(jwt),request);}
    @GetMapping("/friend-requests") List<RequestView> requests(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="incoming") String direction){return service.requests(user(jwt),direction);}
    @PostMapping("/friend-requests/{id}/accept") RequestView accept(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return service.handle(user(jwt),id,true);}
    @PostMapping("/friend-requests/{id}/reject") RequestView reject(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){return service.handle(user(jwt),id,false);}
    @GetMapping("/friends") List<FriendView> friends(@AuthenticationPrincipal Jwt jwt){return service.friends(user(jwt));}
    @DeleteMapping("/friends/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void remove(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){service.remove(user(jwt),id);}
    @PutMapping("/blocks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void block(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){service.block(user(jwt),id);}
    @DeleteMapping("/blocks/{id}") @ResponseStatus(HttpStatus.NO_CONTENT) void unblock(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id){service.unblock(user(jwt),id);}
}
