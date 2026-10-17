package com.example.im.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

final class AuthDtos {
    private AuthDtos() {}

    record RegisterRequest(@NotBlank @Size(min=3,max=128) String account,
                           @NotBlank @Size(min=8,max=72) String password,
                           @NotBlank @Size(max=64) String nickname,
                           @NotNull UUID deviceId) {}
    record LoginRequest(@NotBlank String account, @NotBlank String password, @NotNull UUID deviceId) {}
    record RefreshRequest(@NotBlank String refreshToken) {}
    record LogoutRequest(@NotBlank String refreshToken) {}
    record UpdateProfileRequest(@NotBlank @Size(max=64) String nickname, @Size(max=512) String avatarKey) {}
    record UserView(UUID id, String account, String nickname, String avatarKey, Instant createdAt) {}
    record TokenResponse(String tokenType, String accessToken, long expiresIn,
                         String refreshToken, UserView user) {}
}
