package com.apiforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import jakarta.servlet.Filter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;

import com.apiforge.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * An exception thrown by a servlet filter never reaches Spring MVC's exception handlers; the
 * container forwards it to {@code /error}. This proves such failures still produce the
 * documented problem+json shape without leaking the exception message.
 */
@IntegrationTest
@Import(ContainerErrorIntegrationTest.FailingFilterConfig.class)
class ContainerErrorIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void exceptionInFilterIsRenderedAsProblemJson() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/test/filter-failure")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(500);
        assertThat(response.headers().firstValue("Content-Type"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("application/problem+json"));
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.path("requestId").asString()).isEqualTo(requestId);
        assertThat(body.path("instance").asString()).isEqualTo("/test/filter-failure");
        assertThat(response.body()).doesNotContain("filter exploded").doesNotContain("Exception");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingFilterConfig {

        @Bean
        FilterRegistrationBean<Filter> failingFilter() {
            Filter filter = (request, response, chain) -> {
                throw new IllegalStateException("filter exploded with internal detail");
            };
            FilterRegistrationBean<Filter> registration = new FilterRegistrationBean<>(filter);
            registration.addUrlPatterns("/test/filter-failure");
            // Before Spring Security, so the failure happens regardless of authentication.
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
            return registration;
        }
    }
}
