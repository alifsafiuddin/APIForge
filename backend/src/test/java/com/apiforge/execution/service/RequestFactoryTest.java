package com.apiforge.execution.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.apiforge.environment.domain.Environment.AuthType;
import com.apiforge.environment.domain.Environment.ExecutionPolicy;
import com.apiforge.environment.service.EnvironmentViews.ExecutionEnvironment;
import com.apiforge.outbound.OutboundRequest;
import com.apiforge.outbound.OutboundRequest.Header;
import com.apiforge.testcase.domain.RequestTemplate;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.Body;

class RequestFactoryTest {

    private static final String SECRET = "p@ss/word+1";

    private static ExecutionEnvironment environment(AuthType type, Map<String, Object> config) {
        return new ExecutionEnvironment(UUID.randomUUID(), "Staging", "https://api.example.com/v1", 1500, true,
                ExecutionPolicy.READ_ONLY, type, config, Map.of("Accept", "application/json", "X-Tenant", "{{TENANT}}"),
                Map.of("TENANT", "acme", "USER", "alice"), Map.of("TOKEN", SECRET));
    }

    private static RequestTemplate template(Map<String, Object> path, Map<String, Object> query, Map<String, String> headers,
            Body body, AuthMode auth) {
        return new RequestTemplate(path, query, headers, body, auth);
    }

    private static String header(OutboundRequest request, String name) {
        return request.headers().stream().filter(header -> header.name().equalsIgnoreCase(name)).map(Header::value)
                .findFirst().orElse(null);
    }

    private static RequestFactory.Prepared prepare(ExecutionEnvironment environment, String path, RequestTemplate template)
            throws RequestFactory.UnresolvedVariableException {
        return new RequestFactory(environment, "X-Request-Id").prepare("POST", path, template, "corr-1");
    }

    @Test
    void buildsTheUrlWithEncodedPathAndQueryValues() throws Exception {
        Map<String, Object> query = new LinkedHashMap<>();
        query.put("q", "x y&z=1");
        query.put("tags", List.of("red", "{{TENANT}}"));
        RequestTemplate template = template(Map.of("id", "a/b?c#d e", "n", 42), query, Map.of(), Body.none(), AuthMode.NONE);

        OutboundRequest request = prepare(environment(AuthType.NONE, Map.of()), "/items/{id}/parts/{n}", template).request();

        // A path value can never add segments, a query or a fragment.
        assertThat(request.url()).isEqualTo(
                "https://api.example.com/v1/items/a%2Fb%3Fc%23d%20e/parts/42?q=x%20y%26z%3D1&tags=red&tags=acme");
        assertThat(request.readTimeout().toMillis()).isEqualTo(1500);
        assertThat(request.followRedirects()).isTrue();
    }

    @Test
    void resolvesVariablesInHeadersAndBodiesAndAppliesHeaderPrecedence() throws Exception {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("owner", "{{USER}}");
        value.put("nested", Map.of("tenant", "{{ TENANT }}"));
        value.put("count", 3);
        RequestTemplate template = template(Map.of(), Map.of(), Map.of("accept", "text/plain", "X-Request-Id", "mine"),
                Body.json("application/json", value), AuthMode.ENVIRONMENT);

        OutboundRequest request = prepare(environment(AuthType.NONE, Map.of()), "/things", template).request();

        assertThat(new String(request.body(), StandardCharsets.UTF_8))
                .isEqualTo("{\"owner\":\"alice\",\"nested\":{\"tenant\":\"acme\"},\"count\":3}");
        assertThat(request.contentType()).isEqualTo("application/json");
        assertThat(header(request, "Accept")).as("test headers override defaults").isEqualTo("text/plain");
        assertThat(header(request, "X-Tenant")).isEqualTo("acme");
        assertThat(header(request, "X-Request-Id")).as("the correlation id always wins").isEqualTo("corr-1");
        assertThat(request.headers().stream().filter(header -> header.name().equalsIgnoreCase("accept"))).hasSize(1);
    }

    @Test
    void unresolvedVariablesAndHeaderInjectionStopTheRequest() {
        ExecutionEnvironment environment = environment(AuthType.NONE, Map.of());

        assertThatThrownBy(() -> prepare(environment, "/x",
                template(Map.of(), Map.of("q", "{{MISSING}}"), Map.of(), Body.none(), AuthMode.NONE)))
                .isInstanceOf(RequestFactory.UnresolvedVariableException.class)
                .hasMessage("The variable 'MISSING' is not set in environment 'Staging'.");
        ExecutionEnvironment injecting = new ExecutionEnvironment(UUID.randomUUID(), "Bad", "https://api.example.com", 1000,
                false, ExecutionPolicy.READ_ONLY, AuthType.NONE, Map.of(), Map.of("X-A", "{{EVIL}}"),
                Map.of("EVIL", "a\r\nInjected: 1"), Map.of());
        assertThatThrownBy(() -> prepare(injecting, "/x", template(Map.of(), Map.of(), Map.of(), Body.none(), AuthMode.NONE)))
                .isInstanceOf(RequestFactory.UnresolvedVariableException.class).hasMessageContaining("line breaks");
        assertThatThrownBy(() -> prepare(environment, "/items/{id}",
                template(Map.of(), Map.of(), Map.of(), Body.none(), AuthMode.NONE)))
                .hasMessageContaining("'id' has no value");
    }

    @Test
    void appliesEachAuthenticationTypeAndRedactsTheCredentials() throws Exception {
        RequestTemplate template = template(Map.of(), Map.of(), Map.of(), Body.none(), AuthMode.ENVIRONMENT);

        OutboundRequest bearer = prepare(environment(AuthType.BEARER, Map.of("tokenVariable", "TOKEN")), "/x", template)
                .request();
        RequestFactory.Prepared basic = prepare(environment(AuthType.BASIC,
                Map.of("usernameVariable", "USER", "passwordVariable", "TOKEN")), "/x", template);
        RequestFactory.Prepared headerKey = prepare(environment(AuthType.API_KEY_HEADER,
                Map.of("headerName", "X-Key", "valueVariable", "TOKEN")), "/x", template);
        RequestFactory.Prepared queryKey = prepare(environment(AuthType.API_KEY_QUERY,
                Map.of("parameterName", "api_key", "valueVariable", "TOKEN")), "/x", template);

        assertThat(header(bearer, "Authorization")).isEqualTo("Bearer " + SECRET);
        String basicToken = Base64.getEncoder().encodeToString(("alice:" + SECRET).getBytes(StandardCharsets.UTF_8));
        assertThat(header(basic.request(), "Authorization")).isEqualTo("Basic " + basicToken);
        assertThat(header(headerKey.request(), "X-Key")).isEqualTo(SECRET);
        assertThat(headerKey.request().sensitiveHeaders()).contains("x-key", "authorization");
        assertThat(queryKey.request().url()).isEqualTo("https://api.example.com/v1/x?api_key=p%40ss%2Fword%2B1");
        for (RequestFactory.Prepared prepared : List.of(basic, headerKey, queryKey)) {
            assertThat(prepared.snapshot().toString()).doesNotContain(SECRET).doesNotContain("p%40ss%2Fword%2B1")
                    .doesNotContain(basicToken).contains("«redacted»");
        }
        assertThat(queryKey.snapshot().get("url")).isEqualTo("https://api.example.com/v1/x?api_key=«redacted»");
    }

    @Test
    void authenticationModesNoneAndInvalidNeverSendTheRealCredential() throws Exception {
        ExecutionEnvironment environment = environment(AuthType.BEARER, Map.of("tokenVariable", "TOKEN"));

        OutboundRequest none = prepare(environment, "/x", template(Map.of(), Map.of(), Map.of(), Body.none(), AuthMode.NONE))
                .request();
        OutboundRequest invalid = prepare(environment, "/x",
                template(Map.of(), Map.of(), Map.of(), Body.none(), AuthMode.INVALID)).request();

        assertThat(header(none, "Authorization")).isNull();
        assertThat(header(invalid, "Authorization")).isEqualTo("Bearer " + RequestFactory.INVALID_TOKEN);
        assertThat(invalid.headers().toString()).doesNotContain(SECRET);
    }

    @Test
    void missingCredentialVariablesAreReported() {
        ExecutionEnvironment environment = environment(AuthType.BEARER, Map.of("tokenVariable", "NOPE"));

        assertThatThrownBy(() -> prepare(environment, "/x",
                template(Map.of(), Map.of(), Map.of(), Body.none(), AuthMode.ENVIRONMENT)))
                .hasMessage("The authentication variable 'NOPE' is not set in environment 'Staging'.");
    }

    @Test
    void snapshotsRedactSecretsInBodiesAndTruncateLargeOnes() throws Exception {
        RequestFactory.Prepared secretBody = prepare(environment(AuthType.NONE, Map.of()), "/x", template(Map.of(),
                Map.of(), Map.of(), Body.raw("text/plain", "password={{TOKEN}}"), AuthMode.NONE));
        RequestFactory.Prepared large = prepare(environment(AuthType.NONE, Map.of()), "/x", template(Map.of(), Map.of(),
                Map.of(), Body.raw("text/plain", "x".repeat(RequestFactory.MAX_SNAPSHOT_BODY + 10)), AuthMode.NONE));

        assertThat(new String(secretBody.request().body(), StandardCharsets.UTF_8)).isEqualTo("password=" + SECRET);
        assertThat(((Map<?, ?>) secretBody.snapshot().get("body")).get("value")).isEqualTo("password=«redacted»");
        Map<?, ?> largeBody = (Map<?, ?>) large.snapshot().get("body");
        assertThat((String) largeBody.get("value")).hasSize(RequestFactory.MAX_SNAPSHOT_BODY);
        assertThat(largeBody.get("truncated")).isEqualTo(true);
        assertThat(secretBody.snapshot()).containsEntry("correlationId", "corr-1").containsEntry("auth", "NONE");
    }
}
