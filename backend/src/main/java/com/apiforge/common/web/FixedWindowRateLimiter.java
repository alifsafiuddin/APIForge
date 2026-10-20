package com.apiforge.common.web;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory fixed-window rate limiter keyed by an arbitrary string (for example a client IP).
 * Suitable for a single backend instance (see docs/DECISIONS.md ADR-014); a shared store would
 * be needed for horizontal scaling.
 */
public class FixedWindowRateLimiter {

    /** Upper bound on tracked keys so that a flood of distinct keys cannot exhaust memory. */
    static final int MAX_TRACKED_KEYS = 50_000;

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public FixedWindowRateLimiter(int limit, Duration window, Clock clock) {
        if (limit < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("limit and window must be positive");
        }
        this.limit = limit;
        this.windowMillis = window.toMillis();
        this.clock = clock;
    }

    /**
     * Consumes one permit for {@code key}.
     *
     * @return 0 if allowed, otherwise the number of seconds until the window resets
     */
    public long tryAcquire(String key) {
        long now = clock.millis();
        if (windows.size() >= MAX_TRACKED_KEYS) {
            windows.values().removeIf(window -> window.isExpired(now, windowMillis));
        }
        Window window = windows.compute(key, (k, current) ->
                current == null || current.isExpired(now, windowMillis) ? new Window(now, 1) : current.increment());
        if (window.count() <= limit) {
            return 0;
        }
        long remainingMillis = window.startMillis() + windowMillis - now;
        return Math.max(1, (remainingMillis + 999) / 1000);
    }

    private record Window(long startMillis, int count) {

        Window increment() {
            return new Window(startMillis, count == Integer.MAX_VALUE ? count : count + 1);
        }

        boolean isExpired(long now, long windowMillis) {
            return now - startMillis >= windowMillis;
        }
    }
}
