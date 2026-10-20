package com.apiforge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestUsers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Boots the whole application against a real PostgreSQL container and exercises it over HTTP,
 * so filters, the container error page and Actuator behave exactly as in production.
 */
@IntegrationTest
class ApplicationIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    int port;

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void flywayAppliesAllMigrationsSuccessfully() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).isNotEmpty();
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void baselineMigrationInstallsCitextExtension() {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from pg_extension where extname = 'citext'", Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void healthReportsUpIncludingDatabase() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("db").path("status").asString()).isEqualTo("UP");
        assertThat(body.path("components").path("db").has("details"))
                .as("health details must not be exposed")
                .isFalse();
    }

    @Test
    void readinessAndLivenessProbesAreUp() throws Exception {
        HttpResponse<String> readiness = get("/actuator/health/readiness");
        HttpResponse<String> liveness = get("/actuator/health/liveness");

        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(readiness.body()).path("status").asString()).isEqualTo("UP");
        assertThat(liveness.statusCode()).isEqualTo(200);
    }

    @Test
    void onlyHealthAndInfoActuatorEndpointsAreExposed() throws Exception {
        assertThat(get("/actuator/info").statusCode()).isEqualTo(200);
        // Anonymous callers are stopped by authentication; signed-in users find nothing either.
        assertThat(get("/actuator/env").statusCode()).isEqualTo(401);
        BrowserClient signedIn = TestUsers.register(port).client();
        assertThat(signedIn.get("/actuator/env").status()).isEqualTo(404);
        assertThat(signedIn.get("/actuator/beans").status()).isEqualTo(404);
    }

    @Test
    void systemInfoReturnsBuildVersion() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/info");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("name").asString()).isEqualTo("APIForge AI");
        assertThat(body.path("apiVersion").asString()).isEqualTo("v1");
        assertThat(body.path("version").asString()).isNotBlank();
        assertThat(body.path("serverTime").asString()).isNotBlank();
    }

    @Test
    void unauthenticatedApiRequestReturnsProblemJsonWithCorrelationId() throws Exception {
        HttpResponse<String> response = get("/api/v1/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                value -> assertThat(value).startsWith("application/problem+json"));
        String requestId = response.headers().firstValue("X-Request-Id").orElseThrow();
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(401);
        assertThat(body.path("code").asString()).isEqualTo("UNAUTHENTICATED");
        assertThat(body.path("requestId").asString()).isEqualTo(requestId);
        assertThat(body.path("instance").asString()).isEqualTo("/api/v1/does-not-exist");
        assertThat(body.has("trace")).isFalse();
    }

    @Test
    void responsesCarrySecurityHeaders() throws Exception {
        HttpResponse<String> response = get("/api/v1/system/info");

        assertThat(response.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(response.headers().firstValue("X-Frame-Options")).contains("DENY");
        assertThat(response.headers().firstValue("Content-Security-Policy"))
                .contains("default-src 'none'; frame-ancestors 'none'");
        assertThat(response.headers().firstValue("Referrer-Policy")).contains("no-referrer");
    }

    @Test
    void clientSuppliedRequestIdIsPropagated() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/system/info"))
                .header("X-Request-Id", "client-trace-1234")
                .GET()
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.headers().firstValue("X-Request-Id")).contains("client-trace-1234");
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }
}
