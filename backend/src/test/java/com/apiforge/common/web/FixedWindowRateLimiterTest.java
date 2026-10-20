package com.apiforge.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.apiforge.support.MutableClock;

class FixedWindowRateLimiterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T10:00:00Z"));
    private final FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(3, Duration.ofMinutes(1), clock);

    @Test
    void allowsUpToLimitThenReportsSecondsUntilReset() {
        assertThat(limiter.tryAcquire("10.0.0.1")).isZero();
        assertThat(limiter.tryAcquire("10.0.0.1")).isZero();
        assertThat(limiter.tryAcquire("10.0.0.1")).isZero();

        clock.advance(Duration.ofSeconds(20));

        assertThat(limiter.tryAcquire("10.0.0.1")).isEqualTo(40);
    }

    @Test
    void keysAreIndependent() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }

        assertThat(limiter.tryAcquire("a")).isPositive();
        assertThat(limiter.tryAcquire("b")).isZero();
    }

    @Test
    void windowResetsAfterItExpires() {
        for (int i = 0; i < 4; i++) {
            limiter.tryAcquire("a");
        }

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("a")).isZero();
    }

    @Test
    void neverReportsZeroWhenLimited() {
        for (int i = 0; i < 3; i++) {
            limiter.tryAcquire("a");
        }
        clock.advance(Duration.ofMillis(59_999));

        assertThat(limiter.tryAcquire("a")).isEqualTo(1);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new FixedWindowRateLimiter(0, Duration.ofMinutes(1), clock))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FixedWindowRateLimiter(1, Duration.ZERO, clock))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
