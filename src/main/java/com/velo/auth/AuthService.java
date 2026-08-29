package com.velo.auth;

import com.velo.auth.dto.AuthResponse;
import com.velo.auth.dto.LoginRequest;
import com.velo.auth.dto.RefreshRequest;
import com.velo.auth.dto.UserInfo;
import com.velo.common.exception.UnauthorizedException;
import com.velo.config.AppProperties;
import com.velo.user.User;
import com.velo.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @Transactional
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email().trim().toLowerCase())
                .filter(User::isActive)
                .orElseThrow(() -> new UnauthorizedException("Неверный email или пароль"));
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Неверный email или пароль");
        }
        return issueTokens(user);
    }

    /**
     * Ротация: каждый refresh гасит старый токен и выдаёт новую пару.
     */
    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken token = refreshTokenRepository.findByTokenHash(sha256(request.refreshToken()))
                .filter(t -> !t.isRevoked())
                .filter(t -> t.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new UnauthorizedException("Refresh-токен недействителен или истёк"));

        User user = token.getUser();
        if (!user.isActive()) {
            throw new UnauthorizedException("Пользователь деактивирован");
        }

        token.setRevoked(true);
        return issueTokens(user);
    }

    @Transactional
    public void logout(String refreshToken) {
        refreshTokenRepository.findByTokenHash(sha256(refreshToken))
                .ifPresent(token -> token.setRevoked(true));
    }

    private AuthResponse issueTokens(User user) {
        String accessToken = jwtService.createAccessToken(user);

        String refreshValue = UUID.randomUUID() + "." + UUID.randomUUID();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUser(user);
        refreshToken.setTokenHash(sha256(refreshValue));
        refreshToken.setExpiresAt(Instant.now().plus(properties.getJwt().getRefreshTokenTtl()));
        refreshTokenRepository.save(refreshToken);

        return new AuthResponse(accessToken, refreshValue, UserInfo.from(user));
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
