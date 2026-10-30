package com.apiforge.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TargetApi;
import com.apiforge.support.TestProjects;

/**
 * Nothing secret reaches the log: passwords (registration, failed and successful sign-in, password
 * change), session and CSRF tokens, and environment secrets sent as bearer token, query parameter
 * or header — while the requests themselves are logged.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class SecretLoggingIntegrationTest {

    private static final String PASSWORD = "log-check-Password-" + UUID.randomUUID();
    private static final String NEW_PASSWORD = "log-check-NewPassword-" + UUID.randomUUID();
    private static final String TOKEN = "tok-" + UUID.randomUUID();
    private static final String QUERY_KEY = "qk-" + UUID.randomUUID();
    private static final String HEADER_KEY = "hk-" + UUID.randomUUID();

    @LocalServerPort
    int port;

    @BeforeAll
    static void startTarget() {
        TargetApi.start();
    }

    @Test
    void credentialsAndSecretsNeverAppearInTheLog(CapturedOutput output) {
        BrowserClient browser = new BrowserClient(port);
        String email = "log-check-" + UUID.randomUUID() + "@example.test";
        assertThat(browser.post("/api/v1/auth/register",
                Map.of("email", email, "password", PASSWORD, "displayName", "Log Check")).status()).isEqualTo(201);
        assertThat(new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", email, "password", "wrong-" + PASSWORD)).status()).isEqualTo(401);
        assertThat(new BrowserClient(port).post("/api/v1/auth/login",
                Map.of("email", email, "password", PASSWORD)).status()).isEqualTo(200);

        String projectId = TestProjects.create(browser, "Logging " + UUID.randomUUID());
        String base = "/api/v1/projects/" + projectId;
        String bearer = environment(browser, base, "Bearer", Map.of("type", "BEARER", "tokenVariable", "TOKEN"), "TOKEN", TOKEN);
        String query = environment(browser, base, "Query", Map.of("type", "API_KEY_QUERY", "parameterName", "api_key",
                "valueVariable", "KEY"), "KEY", QUERY_KEY);
        String header = environment(browser, base, "Header", Map.of("type", "API_KEY_HEADER", "headerName", "X-API-Key",
                "valueVariable", "KEY"), "KEY", HEADER_KEY);
        for (String environmentId : new String[] { bearer, query, header }) {
            Response sent = browser.post(base + "/executions", Map.of("environmentId", environmentId, "method", "GET",
                    "path", "/items", "request", Map.of("pathParams", Map.of(), "query", Map.of("page", "1"),
                            "headers", Map.of(), "body", Map.of("mode", "NONE"), "auth", "ENVIRONMENT")));
            assertThat(sent.status()).as(sent.body()).isEqualTo(200);
        }
        assertThat(browser.post("/api/v1/auth/password",
                Map.of("currentPassword", PASSWORD, "newPassword", NEW_PASSWORD)).status()).isEqualTo(204);
        String session = browser.cookie("APIFORGE_SESSION").orElseThrow();
        String csrf = browser.cookie("XSRF-TOKEN").orElseThrow();

        String log = output.getAll();
        assertThat(log).as("requests are logged").contains("outbound GET").contains("/items");
        for (String secret : new String[] { PASSWORD, NEW_PASSWORD, TOKEN, QUERY_KEY, HEADER_KEY, session, csrf }) {
            assertThat(log).as("log must not contain a secret").doesNotContain(secret);
        }
        assertThat(log).doesNotContain(email);
    }

    private static String environment(BrowserClient browser, String base, String name, Map<String, Object> auth,
            String variable, String secret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("baseUrl", TargetApi.BASE_URL);
        body.put("auth", auth);
        String id = browser.post(base + "/environments", body).json().path("id").asString();
        assertThat(browser.put(base + "/environments/" + id + "/variables/" + variable,
                Map.of("value", secret, "secret", true)).status()).isEqualTo(200);
        return id;
    }
}
