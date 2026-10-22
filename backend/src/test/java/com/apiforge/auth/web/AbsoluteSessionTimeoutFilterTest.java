package com.apiforge.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.util.unit.DataSize;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.auth.service.CredentialEpochs;
import com.apiforge.config.ApiForgeProperties;
import com.apiforge.support.MutableClock;

class AbsoluteSessionTimeoutFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
    private final UUID userId = UUID.randomUUID();
    private final Map<UUID, Long> changedAt = new HashMap<>();
    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(properties(Duration.ofHours(12)),
            clock, new CredentialEpochs(id -> changedAt.getOrDefault(id, CredentialEpochs.DELETED)));

    @Test
    void keepsSessionWithinAbsoluteLifetime() throws Exception {
        MockHttpSession session = signedInSession();
        clock.advance(Duration.ofHours(11).plusMinutes(59));

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void invalidatesSessionOnceAbsoluteLifetimeIsReachedEvenIfActive() throws Exception {
        MockHttpSession session = signedInSession();
        clock.advance(Duration.ofHours(12));

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void keepsSessionOfUserWhosePasswordNeverChanged() throws Exception {
        changedAt.put(userId, 0L);
        MockHttpSession session = signedInSession(userId);

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void invalidatesSessionSignedInBeforePasswordChange() throws Exception {
        MockHttpSession session = signedInSession(userId);
        clock.advance(Duration.ofMinutes(5));
        changedAt.put(userId, clock.millis());

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void keepsSessionEstablishedInTheSameMillisecondAsThePasswordChange() throws Exception {
        changedAt.put(userId, clock.millis());
        MockHttpSession session = signedInSession(userId);

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isFalse();
    }

    @Test
    void invalidatesSessionOfDeletedUser() throws Exception {
        MockHttpSession session = signedInSession(userId);

        filter.doFilter(request(session), new MockHttpServletResponse(), new MockFilterChain());

        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void ignoresRequestsWithoutSession() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getSession(false)).isNull();
    }

    private MockHttpSession signedInSession() {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(AbsoluteSessionTimeoutFilter.AUTHENTICATED_AT, clock.millis());
        return session;
    }

    private MockHttpSession signedInSession(UUID user) {
        MockHttpSession session = signedInSession();
        AuthenticatedUser principal = new AuthenticatedUser(user, "ada@example.com", "Ada", true, null);
        session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, new SecurityContextImpl(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities())));
        return session;
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.setSession(session);
        return request;
    }

    private static ApiForgeProperties properties(Duration absoluteTimeout) {
        return new ApiForgeProperties(new ApiForgeProperties.Cors(List.of()),
                new ApiForgeProperties.Auth(true, 4, 5, Duration.ofMinutes(15), absoluteTimeout, 10, 5, false),
                new ApiForgeProperties.Specification(DataSize.ofMegabytes(5), 2000, 100, 50));
    }
}
