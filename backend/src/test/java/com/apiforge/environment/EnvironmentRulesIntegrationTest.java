package com.apiforge.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TargetApi;
import com.apiforge.support.TestProjects;
import com.apiforge.support.TestUsers;

/**
 * Environment rules not covered elsewhere: every authentication type, variable and header limits,
 * duplicate names, and deletion (secrets gone, runs keep their history and the environment name).
 */
@IntegrationTest
class EnvironmentRulesIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    BrowserClient browser;
    String projectId;
    String environments;

    @BeforeAll
    static void startTarget() {
        TargetApi.start();
    }

    @BeforeEach
    void setUp() {
        browser = TestUsers.register(port).client();
        projectId = TestProjects.create(browser, "Environment rules " + UUID.randomUUID());
        environments = "/api/v1/projects/" + projectId + "/environments";
    }

    private static Map<String, Object> environment(String name, Map<String, Object> auth) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("baseUrl", TargetApi.BASE_URL);
        body.put("timeoutMs", 2000);
        body.put("auth", auth);
        return body;
    }

    private static List<String> fields(Response response) {
        return response.json().path("errors").valueStream().map(error -> error.path("field").asString()).toList();
    }

    @Test
    void everyAuthenticationTypeIsStoredWithItsVariableNames() {
        Map<String, Map<String, Object>> auths = Map.of(
                "Bearer", Map.of("type", "BEARER", "tokenVariable", "TOKEN"),
                "Basic", Map.of("type", "BASIC", "usernameVariable", "USER", "passwordVariable", "PASS"),
                "Query key", Map.of("type", "API_KEY_QUERY", "parameterName", "api_key", "valueVariable", "KEY"),
                "None", Map.of("type", "NONE"));

        auths.forEach((name, auth) -> {
            Response created = browser.post(environments, environment(name, auth));
            assertThat(created.status()).as("%s: %s", name, created.body()).isEqualTo(201);
            assertThat(created.json().path("auth").path("type").asString()).isEqualTo(auth.get("type"));
        });
        Response basic = browser.get(environments).json().valueStream()
                .filter(env -> env.path("name").asString().equals("Basic")).findFirst()
                .map(env -> browser.get(environments + "/" + env.path("id").asString())).orElseThrow();
        assertThat(basic.json().path("auth").path("config").path("usernameVariable").asString()).isEqualTo("USER");
        assertThat(basic.json().path("auth").path("config").path("passwordVariable").asString()).isEqualTo("PASS");
    }

    @Test
    void invalidAuthenticationSettingsAreReportedPerField() {
        Response bearer = browser.post(environments, environment("B", Map.of("type", "BEARER", "tokenVariable", "not a name")));
        Response basic = browser.post(environments, environment("C", Map.of("type", "BASIC")));
        Response query = browser.post(environments, environment("Q",
                Map.of("type", "API_KEY_QUERY", "parameterName", "key with spaces", "valueVariable", "KEY")));
        Response header = browser.post(environments, environment("H",
                Map.of("type", "API_KEY_HEADER", "headerName", "Host", "valueVariable", "KEY")));

        assertThat(fields(bearer)).containsExactly("auth.tokenVariable");
        assertThat(fields(basic)).containsExactlyInAnyOrder("auth.usernameVariable", "auth.passwordVariable");
        assertThat(fields(query)).containsExactly("auth.parameterName");
        assertThat(fields(header)).containsExactly("auth.headerName");
        assertThat(browser.get(environments).json()).isEmpty();
    }

    @Test
    void generalSettingsAreValidated() {
        Map<String, Object> body = environment("", Map.of("type", "NONE"));
        body.put("timeoutMs", 50);
        body.put("baseUrl", TargetApi.BASE_URL + "/api?debug=1");
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("bad header", "x");
        body.put("defaultHeaders", headers);

        Response rejected = browser.post(environments, body);

        assertThat(rejected.status()).isEqualTo(400);
        assertThat(fields(rejected)).contains("name", "timeoutMs", "baseUrl", "defaultHeaders");
    }

    @Test
    void tooManyDefaultHeadersAndDuplicateNamesAreRejected() {
        Map<String, Object> body = environment("Headers", Map.of("type", "NONE"));
        Map<String, Object> headers = new LinkedHashMap<>();
        for (int index = 0; index < 31; index++) {
            headers.put("X-Header-" + index, "v");
        }
        body.put("defaultHeaders", headers);
        assertThat(fields(browser.post(environments, body))).contains("defaultHeaders");

        assertThat(browser.post(environments, environment("Twin", Map.of("type", "NONE"))).status()).isEqualTo(201);
        Response duplicate = browser.post(environments, environment("twin", Map.of("type", "NONE")));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("DUPLICATE_NAME");
    }

    @Test
    void variableNamesValuesAndCountAreLimited() {
        String id = browser.post(environments, environment("Vars", Map.of("type", "NONE"))).json().path("id").asString();
        String variables = environments + "/" + id + "/variables/";

        assertThat(browser.put(variables + "1BAD", Map.of("value", "x", "secret", false)).status()).isEqualTo(400);
        assertThat(browser.put(variables + "OK", Map.of("value", "line\nbreak", "secret", false)).status()).isEqualTo(400);
        assertThat(browser.put(variables + "OK", Map.of("value", "x".repeat(4097), "secret", false)).status()).isEqualTo(400);
        assertThat(browser.put(variables + "OK", Map.of("value", "x".repeat(4096), "secret", false)).status()).isEqualTo(200);

        for (int index = 1; index < 100; index++) {
            assertThat(browser.put(variables + "V" + index, Map.of("value", "v", "secret", false)).status()).isEqualTo(200);
        }
        Response tooMany = browser.put(variables + "ONE_MORE", Map.of("value", "v", "secret", false));
        assertThat(tooMany.status()).isEqualTo(400);
        assertThat(tooMany.json().path("detail").asString()).contains("at most 100 variables");
        // Updating an existing variable is still allowed at the limit.
        assertThat(browser.put(variables + "OK", Map.of("value", "changed", "secret", false)).status()).isEqualTo(200);
    }

    @Test
    void deletingASecretIsAudited() {
        String id = browser.post(environments, environment("Secret", Map.of("type", "NONE"))).json().path("id").asString();
        browser.put(environments + "/" + id + "/variables/TOKEN", Map.of("value", "s3cr3t-value", "secret", true));

        assertThat(browser.delete(environments + "/" + id + "/variables/TOKEN").status()).isEqualTo(204);

        assertThat(browser.get(environments + "/" + id).json().path("variables")).isEmpty();
        assertThat(jdbc.queryForList("select details->>'action' from audit_events where event_type = 'SECRET_CHANGED' "
                + "and project_id = ? order by occurred_at", String.class, UUID.fromString(projectId)))
                .containsExactly("set", "deleted");
    }

    @Test
    void deletingAnEnvironmentRemovesItsSecretsButRunsKeepTheirHistory() {
        Map<String, Object> body = environment("Doomed", Map.of("type", "API_KEY_HEADER", "headerName", "X-API-Key",
                "valueVariable", "API_KEY"));
        String id = browser.post(environments, body).json().path("id").asString();
        browser.put(environments + "/" + id + "/variables/API_KEY", Map.of("value", TargetApi.API_KEY, "secret", true));
        Map<String, Object> request = Map.of("pathParams", Map.of(), "query", Map.of(), "headers", Map.of(),
                "body", Map.of("mode", "NONE"), "auth", "ENVIRONMENT");
        Response executed = browser.post("/api/v1/projects/" + projectId + "/executions",
                Map.of("environmentId", id, "method", "GET", "path", "/items", "request", request));
        assertThat(executed.status()).as(executed.body()).isEqualTo(200);
        String runId = executed.json().path("testRunId").asString();

        assertThat(browser.delete(environments + "/" + id).status()).isEqualTo(204);

        assertThat(browser.get(environments + "/" + id).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from environment_variables where environment_id = ?",
                Integer.class, UUID.fromString(id))).isZero();
        Response run = browser.get("/api/v1/projects/" + projectId + "/runs/" + runId);
        assertThat(run.status()).isEqualTo(200);
        assertThat(run.json().path("environmentId").isNull()).isTrue();
        assertThat(run.json().path("environmentName").asString()).isEqualTo("Doomed");
        assertThat(jdbc.queryForList("select details->>'action' from audit_events where event_type = 'ENVIRONMENT_CHANGED' "
                + "and project_id = ?", String.class, UUID.fromString(projectId))).contains("deleted");
    }

    @Test
    void anotherUsersEnvironmentCannotBeChangedOrDeleted() {
        String id = browser.post(environments, environment("Mine", Map.of("type", "NONE"))).json().path("id").asString();
        BrowserClient intruder = TestUsers.register(port).client();

        assertThat(intruder.delete(environments + "/" + id).status()).isEqualTo(404);
        assertThat(intruder.put(environments + "/" + id + "/variables/X", Map.of("value", "v", "secret", false)).status())
                .isEqualTo(404);
        assertThat(intruder.delete(environments + "/" + id + "/variables/X").status()).isEqualTo(404);
        assertThat(browser.get(environments + "/" + id).status()).isEqualTo(200);
    }
}
