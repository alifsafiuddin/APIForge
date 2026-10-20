package com.apiforge.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RequestCorrelationFilterTest {

    private final RequestCorrelationFilter filter = new RequestCorrelationFilter();

    @Test
    void generatesIdWhenHeaderMissingAndExposesItDuringRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/system/info");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> mdcDuringRequest = new AtomicReference<>();

        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                mdcDuringRequest.set(MDC.get(RequestCorrelationFilter.MDC_KEY));
            }
        });

        String header = response.getHeader(RequestCorrelationFilter.HEADER);
        assertThat(header).matches("[0-9a-f-]{36}");
        assertThat(mdcDuringRequest.get()).isEqualTo(header);
        assertThat(RequestCorrelationFilter.currentRequestId(request)).isEqualTo(header);
    }

    @Test
    void reusesWellFormedClientId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestCorrelationFilter.HEADER, "abc-123_DEF.456");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("abc-123_DEF.456");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "short",                                   // too short
            "id with spaces",                          // unsafe characters
            "evil\r\nX-Injected: true",                // header/log injection
            "<script>alert(1)</script>",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" // 65 chars
    })
    void replacesUnsafeClientIds(String unsafe) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        request.addHeader(RequestCorrelationFilter.HEADER, unsafe);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getHeader(RequestCorrelationFilter.HEADER))
                .isNotEqualTo(unsafe)
                .matches("[0-9a-f-]{36}");
    }

    @Test
    void clearsMdcAfterRequestEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse response = new MockHttpServletResponse();

        MockFilterChain failingChain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new IllegalStateException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request, response, failingChain))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }
}
