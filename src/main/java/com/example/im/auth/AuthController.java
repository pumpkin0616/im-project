package com.example.im.auth;

import com.example.im.auth.AuthDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// 表示这个类负责接收HTTP请求，方法返回值会自动转换成JSON。
@RestController
// 表示这个类中的接口都以 /api/v1 开头。
@RequestMapping("/api/v1")
public final class AuthController {
  private final AuthService service;

  public AuthController(AuthService service) {
    this.service = service;
  }

  @PostMapping("/auth/register") // 接收POST请求
  @ResponseStatus(HttpStatus.CREATED) // 成功返回HTTP 201
  TokenResponse register(
      // @Valid 检查参数是否合法
      // @RequestBody 把JSON转换成Java对象
      @Valid @RequestBody RegisterRequest request) {
    return service.register(request);
  }

  @PostMapping("/auth/login")
  TokenResponse login(@Valid @RequestBody LoginRequest request) {
    return service.login(request);
  }

  @PostMapping("/auth/refresh")
  TokenResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return service.refresh(request.refreshToken());
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void logout(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LogoutRequest request) {
    service.logout(request.refreshToken(), UUID.fromString(jwt.getSubject()));
  }

  @GetMapping("/users/me")
  UserView me(@AuthenticationPrincipal Jwt jwt) {
    return service.me(UUID.fromString(jwt.getSubject()));
  }

  @PutMapping("/users/me")
  UserView update(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody UpdateProfileRequest request) {
    return service.update(UUID.fromString(jwt.getSubject()), request);
  }
}
