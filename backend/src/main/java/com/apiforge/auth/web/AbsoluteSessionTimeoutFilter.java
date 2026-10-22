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
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.auth.service.CredentialEpochs;
import com.apiforge.config.ApiForgeProperties;

/**
 * Ends sessions a fixed time after sign-in, however active they are, and sessions signed in before
 * the user's password changed. The servlet container
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
    private final CredentialEpochs epochs;

    public AbsoluteSessionTimeoutFilter(ApiForgeProperties properties, Clock clock, CredentialEpochs epochs) {
        this.absoluteTimeout = properties.auth().absoluteSessionTimeout();
        this.clock = clock;
        this.epochs = epochs;
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

    /** Too old, or signed in before the user's password changed (or the account was deleted). */
    private boolean isExpired(HttpSession session) {
        Object value = session.getAttribute(AUTHENTICATED_AT);
        long authenticatedAt = value instanceof Long millis ? millis : session.getCreationTime();
        if (clock.millis() - authenticatedAt >= absoluteTimeout.toMillis()) {
            return true;
        }
        if (session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY) instanceof SecurityContext context
                && context.getAuthentication() != null
                && context.getAuthentication().getPrincipal() instanceof AuthenticatedUser user) {
            return epochs.changedAt(user.id()) > authenticatedAt;
        }
        return false;
    }
}
