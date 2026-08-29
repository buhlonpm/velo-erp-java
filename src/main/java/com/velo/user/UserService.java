package com.velo.user;

import com.velo.auth.RefreshTokenRepository;
import com.velo.common.exception.ConflictException;
import com.velo.common.exception.NotFoundException;
import com.velo.security.AppPermissions;
import com.velo.user.dto.CreateUserRequest;
import com.velo.user.dto.UpdateUserRequest;
import com.velo.user.dto.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;

    public List<UserResponse> findAll() {
        return userRepository.findAll(Sort.by("createdAt")).stream()
                .map(UserResponse::from)
                .toList();
    }

    @Transactional
    public UserResponse create(CreateUserRequest request) {
        String email = request.email().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Пользователь с таким email уже существует");
        }
        User user = new User();
        user.setEmail(email);
        user.setFullName(request.fullName());
        user.setPhone(request.phone() != null ? request.phone() : "");
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(request.role());
        if (request.permissions() != null) {
            user.setPermissions(validatePermissions(request.permissions()));
        }
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse update(UUID id, UpdateUserRequest request, UUID actorId) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));

        if (request.fullName() != null) {
            user.setFullName(request.fullName());
        }
        if (request.phone() != null) {
            user.setPhone(request.phone());
        }
        if (request.role() != null && request.role() != user.getRole()) {
            if (id.equals(actorId)) {
                throw new ConflictException("Нельзя менять роль самому себе");
            }
            user.setRole(request.role());
            invalidateSessions(user);
        }
        if (request.permissions() != null
                && !request.permissions().equals(user.getPermissions())) {
            user.setPermissions(validatePermissions(request.permissions()));
            invalidateSessions(user);
        }
        if (request.active() != null) {
            if (Boolean.FALSE.equals(request.active()) && id.equals(actorId)) {
                throw new ConflictException("Нельзя деактивировать самого себя");
            }
            user.setActive(request.active());
            if (!request.active()) {
                invalidateSessions(user);
            }
        }
        if (request.password() != null && !request.password().isBlank()) {
            user.setPasswordHash(passwordEncoder.encode(request.password()));
            invalidateSessions(user);
        }
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public void delete(UUID id, UUID actorId) {
        if (id.equals(actorId)) {
            throw new ConflictException("Нельзя удалить самого себя");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Пользователь не найден"));
        userRepository.delete(user);
    }

    /** Мгновенная инвалидация сессий: bump версии убивает все access-токены
     * (конвертер сверяет claim tv), отзыв refresh-токенов закрывает продление.
     * Вызывается при смене роли, прав, пароля и деактивации. */
    private void invalidateSessions(User user) {
        user.setTokenVersion(user.getTokenVersion() + 1);
        refreshTokenRepository.findAllByUserIdAndRevokedFalse(user.getId())
                .forEach(token -> token.setRevoked(true));
    }

    private Set<String> validatePermissions(Set<String> permissions) {
        for (String permission : permissions) {
            if (!AppPermissions.ALL.contains(permission)) {
                throw new ConflictException("Неизвестное право: " + permission);
            }
        }
        return Set.copyOf(permissions);
    }
}
