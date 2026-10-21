package com.apiforge.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.util.unit.DataSize;

import com.apiforge.common.error.ProblemResponseWriter;
import com.apiforge.common.web.PayloadTooLargeException;

import tools.jackson.databind.json.JsonMapper;

class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(properties(DataSize.ofKilobytes(100)),
            new ProblemResponseWriter(JsonMapper.builder().build()));

    @Test
    void declaredOversizedBodyIsRejectedWithoutCallingTheChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/x/specs");
        request.setContent(new byte[(int) filter.maxBytes() + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("PAYLOAD_TOO_LARGE");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void bodyWithoutDeclaredLengthIsCountedWhileRead() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/x/specs") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked transfer encoding
            }
        };
        request.setContent(new byte[(int) filter.maxBytes() + 10]);
        AtomicReference<ServletRequest> seen = new AtomicReference<>();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest req, ServletResponse res) {
                seen.set(req);
            }
        });

        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
                .isInstanceOf(PayloadTooLargeException.class);
    }

    @Test
    void bodiesWithinTheLimitPassUnchanged() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects");
        request.setContent("{\"name\":\"ok\"}".getBytes());
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(chain.getRequest()).isSameAs(request);
    }

    private static ApiForgeProperties properties(DataSize specMax) {
        return new ApiForgeProperties(new ApiForgeProperties.Cors(List.of()),
                new ApiForgeProperties.Auth(true, 4, 5, Duration.ofMinutes(15), Duration.ofHours(12), 10, 5, false),
                new ApiForgeProperties.Specification(specMax, 2000, 100, 50));
    }
}
