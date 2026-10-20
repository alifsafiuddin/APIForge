package com.apiforge.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;

import com.apiforge.config.ApiForgeProperties;
import com.apiforge.support.MutableClock;

class AbsoluteSessionTimeoutFilterTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-21T08:00:00Z"));
    private final AbsoluteSessionTimeoutFilter filter = new AbsoluteSessionTimeoutFilter(properties(Duration.ofHours(12)),
            clock);

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

    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/projects");
        request.setSession(session);
        return request;
    }

    private static ApiForgeProperties properties(Duration absoluteTimeout) {
        return new ApiForgeProperties(new ApiForgeProperties.Cors(List.of()),
                new ApiForgeProperties.Auth(true, 4, 5, Duration.ofMinutes(15), absoluteTimeout, 10, 5, false));
    }
}
