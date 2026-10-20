package com.apiforge.auth.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.error.ProblemResponseWriter;
import com.apiforge.common.web.FixedWindowRateLimiter;
import com.apiforge.config.ApiForgeProperties;

/**
 * Per-client-IP limits on sign-in and registration attempts, applied before any password
 * hashing so that brute-force traffic stays cheap to reject. Complements the per-account lockout.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthRateLimitFilter extends OncePerRequestFilter {

    static final String LOGIN_PATH = "/api/v1/auth/login";
    static final String REGISTER_PATH = "/api/v1/auth/register";

    private final FixedWindowRateLimiter loginLimiter;
    private final FixedWindowRateLimiter registrationLimiter;
    private final ProblemResponseWriter problemWriter;

    public AuthRateLimitFilter(ApiForgeProperties properties, Clock clock, ProblemResponseWriter problemWriter) {
        ApiForgeProperties.Auth auth = properties.auth();
        this.loginLimiter = new FixedWindowRateLimiter(auth.loginRequestsPerMinute(), Duration.ofMinutes(1), clock);
        this.registrationLimiter =
                new FixedWindowRateLimiter(auth.registrationRequestsPerMinute(), Duration.ofMinutes(1), clock);
        this.problemWriter = problemWriter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        return !LOGIN_PATH.equals(path) && !REGISTER_PATH.equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        FixedWindowRateLimiter limiter = LOGIN_PATH.equals(request.getRequestURI()) ? loginLimiter : registrationLimiter;
        long retryAfterSeconds = limiter.tryAcquire(request.getRemoteAddr());
        if (retryAfterSeconds > 0) {
            response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
            problemWriter.write(request, response, ErrorCode.RATE_LIMITED,
                    "Too many attempts. Try again in %d seconds.".formatted(retryAfterSeconds));
            return;
        }
        chain.doFilter(request, response);
    }
}
