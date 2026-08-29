package com.velo.auth;

import com.velo.common.exception.TooManyRequestsException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Защита логина от брутфорса: MAX_FAILURES неудачных попыток по ключу
 * «IP + email» → блокировка на BLOCK_DURATION. Успешный вход сбрасывает счётчик.
 * Хранение в памяти — приложение одноинстансное (Amvera), рестарта хватает на сброс.
 */
@Component
public class LoginRateLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration BLOCK_DURATION = Duration.ofMinutes(10);
    private static final int MAX_ENTRIES = 10_000;

    private record Attempts(int failures, Instant blockedUntil) {}

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public void assertNotBlocked(String key) {
        Attempts current = attempts.get(key);
        if (current == null || current.blockedUntil() == null) {
            return;
        }
        if (Instant.now().isBefore(current.blockedUntil())) {
            long minutesLeft = Duration.between(Instant.now(), current.blockedUntil()).toMinutes() + 1;
            throw new TooManyRequestsException(
                    "Слишком много попыток входа. Попробуйте через " + minutesLeft + " мин.");
        }
        attempts.remove(key);
    }

    public void recordFailure(String key) {
        // защита от раздувания карты при переборе с уникальными ключами
        if (attempts.size() >= MAX_ENTRIES) {
            attempts.clear();
        }
        Attempts current = attempts.get(key);
        int failures = (current == null ? 0 : current.failures()) + 1;
        Instant blockedUntil = failures >= MAX_FAILURES ? Instant.now().plus(BLOCK_DURATION) : null;
        attempts.put(key, new Attempts(failures, blockedUntil));
    }

    public void reset(String key) {
        attempts.remove(key);
    }
}
