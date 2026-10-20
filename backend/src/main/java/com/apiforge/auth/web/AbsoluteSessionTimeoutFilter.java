package com.apiforge.auth.web;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.apiforge.config.ApiForgeProperties;

/**
 * Ends sessions a fixed time after sign-in, however active they are. The servlet container
 * only provides an idle timeout. Runs before Spring Security, so an expired session is seen
 * as unauthenticated on the same request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AbsoluteSessionTimeoutFilter extends OncePerRequestFilter {

    /** Epoch milliseconds of sign-in, written by {@link SessionAuthenticator}. */
    static final String AUTHENTICATED_AT = AbsoluteSessionTimeoutFilter.class.getName() + ".authenticatedAt";

    private final Duration absoluteTimeout;
    private final Clock clock;

    public AbsoluteSessionTimeoutFilter(ApiForgeProperties properties, Clock clock) {
        this.absoluteTimeout = properties.auth().absoluteSessionTimeout();
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && isExpired(session)) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }

    private boolean isExpired(HttpSession session) {
        Object value = session.getAttribute(AUTHENTICATED_AT);
        long authenticatedAt = value instanceof Long millis ? millis : session.getCreationTime();
        return clock.millis() - authenticatedAt >= absoluteTimeout.toMillis();
    }
}
