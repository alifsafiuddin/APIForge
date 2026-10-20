package com.apiforge.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class UserLockoutTest {

    private static final Instant NOW = Instant.parse("2026-10-21T10:00:00Z");
    private static final Duration LOCKOUT = Duration.ofMinutes(15);

    private final User user = new User("a@example.test", "{bcrypt}hash", "A");

    @Test
    void locksOnTheConfiguredFailureAndRestartsTheCounter() {
        for (int i = 1; i < 5; i++) {
            assertThat(user.recordFailedLogin(NOW, 5, LOCKOUT)).isFalse();
        }

        assertThat(user.recordFailedLogin(NOW, 5, LOCKOUT)).isTrue();
        assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(LOCKOUT));
        assertThat(user.getFailedLoginCount()).isZero();
    }

    @Test
    void lockExpires() {
        for (int i = 0; i < 5; i++) {
            user.recordFailedLogin(NOW, 5, LOCKOUT);
        }

        assertThat(user.isLockedAt(NOW.plus(LOCKOUT).minusSeconds(1))).isTrue();
        assertThat(user.isLockedAt(NOW.plus(LOCKOUT))).isFalse();
    }

    @Test
    void successfulLoginResetsFailuresAndLock() {
        user.recordFailedLogin(NOW, 5, LOCKOUT);
        user.recordFailedLogin(NOW, 5, LOCKOUT);

        user.recordSuccessfulLogin(NOW);

        assertThat(user.getFailedLoginCount()).isZero();
        assertThat(user.getLockedUntil()).isNull();
        assertThat(user.getLastLoginAt()).isEqualTo(NOW);
    }
}
