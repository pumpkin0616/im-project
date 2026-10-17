package com.example.im.auth;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1/users")
public final class AvatarController {
    private final AvatarService service;
    public AvatarController(AvatarService service){this.service=service;}

    @PostMapping(value="/me/avatar",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    AuthDtos.UserView upload(@AuthenticationPrincipal Jwt jwt,@RequestPart("file") MultipartFile file){
        return service.upload(UUID.fromString(jwt.getSubject()),file);
    }
    @GetMapping("/{userId}/avatar")
    ResponseEntity<org.springframework.core.io.Resource> download(@PathVariable UUID userId){
        AvatarService.AvatarDownload value=service.download(userId);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .contentType(MediaType.parseMediaType(value.contentType())).body(value.resource());
    }
    @DeleteMapping("/me/avatar")
    ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt){
        service.delete(UUID.fromString(jwt.getSubject()));return ResponseEntity.noContent().build();
    }
}
