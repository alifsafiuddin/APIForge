package com.apiforge.auth.web;

import java.time.Clock;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Component;

/**
 * Turns a verified {@link Authentication} into a server-side session. Login is a JSON endpoint
 * rather than Spring's form login, so the protections a login filter normally applies are
 * applied here explicitly: a new session id (fixation protection) and a new CSRF token.
 */
@Component
public class SessionAuthenticator {

    private final SessionAuthenticationStrategy sessionStrategy;
    private final SecurityContextRepository contextRepository;
    private final Clock clock;
    private final SecurityContextHolderStrategy holderStrategy = SecurityContextHolder.getContextHolderStrategy();

    public SessionAuthenticator(SessionAuthenticationStrategy sessionStrategy,
            SecurityContextRepository contextRepository, Clock clock) {
        this.sessionStrategy = sessionStrategy;
        this.contextRepository = contextRepository;
        this.clock = clock;
    }

    public void establish(Authentication authentication, HttpServletRequest request, HttpServletResponse response) {
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = holderStrategy.createEmptyContext();
        context.setAuthentication(authentication);
        holderStrategy.setContext(context);
        contextRepository.saveContext(context, request, response);
        request.getSession().setAttribute(AbsoluteSessionTimeoutFilter.AUTHENTICATED_AT, clock.millis());
        // The CSRF strategy replaced the token lazily; load it so the new cookie is sent in this response.
        if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken csrfToken) {
            csrfToken.getToken();
        }
    }
}
