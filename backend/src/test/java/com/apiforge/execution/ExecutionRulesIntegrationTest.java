package com.apiforge.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TargetApi;
import com.apiforge.support.TestProjects;
import com.apiforge.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** Which tests a run may contain, and requests that must be refused before anything is sent. */
@IntegrationTest
class ExecutionRulesIntegrationTest {

    @LocalServerPort
    int port;

    BrowserClient browser;
    String base;
    String environmentId;

    @BeforeAll
    static void startTarget() {
        TargetApi.start();
    }

    @BeforeEach
    void setUp() {
        browser = TestUsers.register(port).client();
        base = "/api/v1/projects/" + TestProjects.create(browser, "Rules " + UUID.randomUUID());
        assertThat(browser.postFile(base + "/specs?activate=true", "file", "items.yaml",
                TargetApi.SPEC.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(201);
        assertThat(browser.post(base + "/tests/generate", Map.of()).status()).isEqualTo(200);
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("name", "Target");
        environment.put("baseUrl", TargetApi.BASE_URL);
        environment.put("auth", Map.of("type", "NONE"));
        environmentId = browser.post(base + "/environments", environment).json().path("id").asString();
    }

    private List<JsonNode> tests() {
        return browser.get(base + "/tests?size=100").json().path("content").valueStream().toList();
    }

    private Response start(Map<String, Object> body) {
        Map<String, Object> request = new LinkedHashMap<>(body);
        request.putIfAbsent("environmentId", environmentId);
        return browser.post(base + "/runs", request);
    }

    private int runCount() {
        return browser.get(base + "/runs").json().path("page").path("totalElements").asInt();
    }

    @Test
    void selectedTestsMustAllBeRunnable() {
        JsonNode test = tests().getFirst();
        browser.patch(base + "/tests/" + test.path("id").asString(),
                Map.of("enabled", false, "version", test.path("version").asLong()));

        Response refused = start(Map.of("testIds", List.of(test.path("id").asString())));

        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.json().path("detail").asString()).contains("cannot run").contains(test.path("name").asString());
        assertThat(runCount()).isZero();
    }

    @Test
    void unknownOrForeignTestIdsAreNotFound() {
        Response unknown = start(Map.of("testIds", List.of(UUID.randomUUID().toString())));

        BrowserClient other = TestUsers.register(port).client();
        String otherBase = "/api/v1/projects/" + TestProjects.create(other, "Other " + UUID.randomUUID());
        other.postFile(otherBase + "/specs?activate=true", "file", "items.yaml", TargetApi.SPEC.getBytes(StandardCharsets.UTF_8));
        other.post(otherBase + "/tests/generate", Map.of());
        String foreignTest = other.get(otherBase + "/tests?size=1").json().path("content").get(0).path("id").asString();
        Response foreign = start(Map.of("testIds", List.of(foreignTest)));

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(runCount()).isZero();
    }

    @Test
    void anOperationOrSuiteWithoutRunnableTestsIsRefused() {
        Response noOperation = start(Map.of("operationKey", "GET /does-not-exist"));
        String emptySuite = browser.post(base + "/test-suites", Map.of("name", "Empty")).json().path("id").asString();
        Response noTests = start(Map.of("suiteId", emptySuite));

        assertThat(noOperation.status()).isEqualTo(400);
        assertThat(noTests.status()).isEqualTo(400);
        assertThat(runCount()).isZero();
    }

    @Test
    void anEnvironmentOfAnotherProjectIsNotFound() {
        BrowserClient other = TestUsers.register(port).client();
        String otherBase = "/api/v1/projects/" + TestProjects.create(other, "Other " + UUID.randomUUID());
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("name", "Theirs");
        environment.put("baseUrl", TargetApi.BASE_URL);
        environment.put("auth", Map.of("type", "NONE"));
        String theirs = other.post(otherBase + "/environments", environment).json().path("id").asString();

        assertThat(start(Map.of("environmentId", theirs)).status()).isEqualTo(404);
        assertThat(runCount()).isZero();
    }

    @Test
    void consoleRequestsAreValidatedBeforeSending() {
        Map<String, Object> request = Map.of("pathParams", Map.of(), "query", Map.of(), "headers", Map.of(),
                "body", Map.of("mode", "NONE"), "auth", "ENVIRONMENT");
        int hitsBefore = TargetApi.hits("/items");

        Response badMethod = browser.post(base + "/executions",
                Map.of("environmentId", environmentId, "method", "BREW", "path", "/items", "request", request));
        Response absoluteUrl = browser.post(base + "/executions",
                Map.of("environmentId", environmentId, "method", "GET", "path", "http://169.254.169.254/latest", "request", request));
        Response noPath = browser.post(base + "/executions",
                Map.of("environmentId", environmentId, "method", "GET", "path", "", "request", request));

        assertThat(badMethod.status()).isEqualTo(400);
        assertThat(absoluteUrl.status()).isEqualTo(400);
        assertThat(noPath.status()).isEqualTo(400);
        assertThat(TargetApi.hits("/items")).isEqualTo(hitsBefore);
        assertThat(runCount()).isZero();
    }

    @Test
    void aFinishedRunCannotBeCancelled() {
        Response started = start(Map.of());
        String runId = started.json().path("id").asString();
        long deadline = System.currentTimeMillis() + 30_000;
        while (!browser.get(base + "/runs/" + runId).json().path("status").asString().equals("COMPLETED")) {
            assertThat(System.currentTimeMillis()).as("run did not finish").isLessThan(deadline);
            Thread.onSpinWait();
        }

        Response cancel = browser.post(base + "/runs/" + runId + "/cancel", Map.of());

        assertThat(cancel.status()).isEqualTo(409);
        assertThat(browser.get(base + "/runs/" + runId).json().path("status").asString()).isEqualTo("COMPLETED");
    }
}
