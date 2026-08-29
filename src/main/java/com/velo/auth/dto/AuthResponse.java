package com.velo.auth.dto;

public record AuthResponse(
        String accessToken,
        String refreshToken,
        UserInfo user
) {
}
