package com.apiforge.environment;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TargetApi;
import com.apiforge.support.TestProjects;
import com.apiforge.support.TestUsers;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class EnvironmentIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    BrowserClient browser;
    String projectId;
    String environments;

    @BeforeEach
    void setUp() {
        browser = TestUsers.register(port).client();
        projectId = TestProjects.create(browser, "Environments " + UUID.randomUUID());
        environments = "/api/v1/projects/" + projectId + "/environments";
    }

    static Map<String, Object> environment(String name, String baseUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("baseUrl", baseUrl);
        body.put("timeoutMs", 2000);
        body.put("auth", Map.of("type", "API_KEY_HEADER", "headerName", "X-API-Key", "valueVariable", "API_KEY"));
        body.put("defaultHeaders", Map.of("Accept", "application/json"));
        return body;
    }

    @Test
    void secretsAreEncryptedAtRestAndNeverReturned() {
        Response created = browser.post(environments, environment("Local", TargetApi.BASE_URL + "/"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(created.json().path("baseUrl").asString()).isEqualTo(TargetApi.BASE_URL);
        assertThat(created.json().path("executionPolicy").asString()).isEqualTo("READ_ONLY");

        Response secret = browser.put(environments + "/" + id + "/variables/API_KEY",
                Map.of("value", TargetApi.API_KEY, "secret", true));
        Response plain = browser.put(environments + "/" + id + "/variables/TENANT", Map.of("value", "acme", "secret", false));

        assertThat(secret.status()).as(secret.body()).isEqualTo(200);
        assertThat(secret.json().path("value").isNull()).isTrue();
        assertThat(secret.json().path("hasValue").asBoolean()).isTrue();
        assertThat(secret.body()).doesNotContain(TargetApi.API_KEY);
        assertThat(plain.json().path("value").asString()).isEqualTo("acme");
        JsonNode detail = browser.get(environments + "/" + id).json();
        assertThat(detail.toString()).doesNotContain(TargetApi.API_KEY);
        assertThat(detail.path("variables").findValuesAsString("name")).containsExactly("API_KEY", "TENANT");
        assertThat(detail.path("auth").path("config").path("valueVariable").asString()).isEqualTo("API_KEY");

        Map<String, Object> row = jdbc.queryForMap("""
                select v.plain_value, v.encrypted_value, v.key_id from environment_variables v
                where v.environment_id = ? and v.name = 'API_KEY'""", UUID.fromString(id));
        byte[] ciphertext = (byte[]) row.get("encrypted_value");
        assertThat(row.get("plain_value")).isNull();
        assertThat(row.get("key_id")).isEqualTo("k1");
        assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain(TargetApi.API_KEY);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where details::text like ?", Integer.class,
                "%" + TargetApi.API_KEY + "%")).isZero();
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_events where project_id = ? and event_type = 'SECRET_CHANGED'""",
                Integer.class, UUID.fromString(projectId))).isEqualTo(1);
    }

    @Test
    void switchingASecretToPlainAndBackReplacesTheStoredForm() {
        String id = browser.post(environments, environment("Switch", TargetApi.BASE_URL)).json().path("id").asString();
        browser.put(environments + "/" + id + "/variables/TOKEN", Map.of("value", "first", "secret", true));
        browser.put(environments + "/" + id + "/variables/TOKEN", Map.of("value", "visible", "secret", false));

        assertThat(jdbc.queryForObject("select encrypted_value is null and plain_value = 'visible' from environment_variables "
                + "where environment_id = ? and name = 'TOKEN'", Boolean.class, UUID.fromString(id))).isTrue();
        assertThat(browser.delete(environments + "/" + id + "/variables/TOKEN").status()).isEqualTo(204);
        assertThat(browser.delete(environments + "/" + id + "/variables/TOKEN").status()).isEqualTo(404);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://169.254.169.254", "http://metadata.google.internal", "ftp://files.example.com",
            "http://user:pass@api.example.com", "http://localhost:5432", "http://0x7f.0.0.1:8080", "http://2130706433",
            "http://[::1]:8080", "http://10.0.0.5", "https://api.example.com/v1?key=1", "file:///etc/passwd",
            "api.example.com", "http://api.example.com:22"})
    void unsafeOrInvalidBaseUrlsAreRefusedWhenSaving(String baseUrl) {
        Response response = browser.post(environments, environment("Bad", baseUrl));

        assertThat(response.status()).as(baseUrl).isEqualTo(400);
        assertThat(response.json().path("errors").findValuesAsString("field")).as(baseUrl).contains("baseUrl");
    }

    @Test
    void mutatingPoliciesNeedAnAuthorizationConfirmation() {
        Map<String, Object> body = environment("Staging", TargetApi.BASE_URL);
        body.put("executionPolicy", "ALLOW_DESTRUCTIVE");

        Response refused = browser.post(environments, body);
        body.put("authorizationConfirmed", true);
        Response accepted = browser.post(environments, body);

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.json().path("errors").findValuesAsString("field")).containsExactly("authorizationConfirmed");
        assertThat(accepted.status()).isEqualTo(201);
        assertThat(accepted.json().path("executionPolicy").asString()).isEqualTo("ALLOW_DESTRUCTIVE");
    }

    @Test
    void credentialAndTransportHeadersCannotBeDefaults() {
        Map<String, Object> body = environment("Headers", TargetApi.BASE_URL);
        body.put("defaultHeaders", Map.of("Authorization", "Bearer x", "Host", "evil.example", "X-Ok", "a\r\nb: c"));

        Response response = browser.post(environments, body);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.json().path("errors").findValuesAsString("field")).containsExactlyInAnyOrder(
                "defaultHeaders.Authorization", "defaultHeaders.Host", "defaultHeaders.X-Ok");
    }

    @Test
    void variablesAreValidated() {
        String id = browser.post(environments, environment("Vars", TargetApi.BASE_URL)).json().path("id").asString();

        assertThat(browser.put(environments + "/" + id + "/variables/1bad", Map.of("value", "x", "secret", false)).status())
                .isEqualTo(400);
        assertThat(browser.put(environments + "/" + id + "/variables/OK", Map.of("value", "line\nbreak", "secret", true))
                .status()).isEqualTo(400);
        assertThat(jdbc.queryForObject("select count(*) from environment_variables where environment_id = ?",
                Integer.class, UUID.fromString(id))).isZero();
    }

    @Test
    void updatesUseOptimisticLockingAndUniqueNames() {
        JsonNode first = browser.post(environments, environment("One", TargetApi.BASE_URL)).json();
        browser.post(environments, environment("Two", TargetApi.BASE_URL));
        Map<String, Object> rename = environment("two", TargetApi.BASE_URL);
        rename.put("version", first.path("version").asLong());

        assertThat(browser.put(environments + "/" + first.path("id").asString(), rename).code()).isEqualTo("DUPLICATE_NAME");
        Map<String, Object> update = environment("One renamed", TargetApi.BASE_URL);
        update.put("version", first.path("version").asLong() + 3);
        assertThat(browser.put(environments + "/" + first.path("id").asString(), update).code()).isEqualTo("STALE_VERSION");
        update.put("version", first.path("version").asLong());
        assertThat(browser.put(environments + "/" + first.path("id").asString(), update).json().path("name").asString())
                .isEqualTo("One renamed");
        assertThat(browser.get(environments).json().findValuesAsString("name")).containsExactly("One renamed", "Two");
    }

    @Test
    void otherUsersCannotSeeOrChangeEnvironments() {
        String id = browser.post(environments, environment("Mine", TargetApi.BASE_URL)).json().path("id").asString();
        browser.put(environments + "/" + id + "/variables/API_KEY", Map.of("value", TargetApi.API_KEY, "secret", true));
        BrowserClient intruder = TestUsers.register(port).client();

        assertThat(intruder.get(environments).status()).isEqualTo(404);
        assertThat(intruder.get(environments + "/" + id).status()).isEqualTo(404);
        assertThat(intruder.put(environments + "/" + id + "/variables/API_KEY", Map.of("value", "x", "secret", true))
                .status()).isEqualTo(404);
        assertThat(intruder.delete(environments + "/" + id).status()).isEqualTo(404);
        assertThat(browser.get(environments + "/" + id).status()).isEqualTo(200);
        assertThat(List.of(intruder.post(environments, environment("X", TargetApi.BASE_URL)).status())).containsExactly(404);
    }
}
