package com.apiforge.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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

import tools.jackson.databind.JsonNode;

/**
 * Coverage over HTTP for the items API of {@link TargetApi}: 4 operations with 2 + 4 + 4 + 3 = 13
 * documented responses.
 */
@IntegrationTest
class CoverageIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    BrowserClient browser;
    String projectId;
    String base;

    @BeforeAll
    static void startTarget() {
        TargetApi.start();
    }

    @BeforeEach
    void setUp() {
        browser = TestUsers.register(port).client();
        projectId = TestProjects.create(browser, "Coverage " + UUID.randomUUID());
        base = "/api/v1/projects/" + projectId;
    }

    private void importSpec(String spec, String name) {
        Response imported = browser.postFile(base + "/specs?activate=true", "file", name, spec.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
    }

    private void generate() {
        assertThat(browser.post(base + "/tests/generate", Map.of()).status()).isEqualTo(200);
    }

    private JsonNode coverage(String query) {
        Response response = browser.get(base + "/coverage" + query);
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private static int metric(JsonNode report, String id, String field) {
        for (JsonNode metric : report.path("metrics")) {
            if (metric.path("id").asString().equals(id)) {
                return metric.path(field).asInt();
            }
        }
        throw new AssertionError("No metric " + id);
    }

    private List<JsonNode> tests(String operationKey) {
        List<JsonNode> tests = new ArrayList<>();
        browser.get(base + "/tests?size=100&operationKey=" + operationKey.replace(" ", "%20").replace("{", "%7B").replace("}", "%7D"))
                .json().path("content").forEach(tests::add);
        return tests;
    }

    private JsonNode test(String operationKey, String scenarioKey) {
        for (JsonNode test : tests(operationKey)) {
            JsonNode detail = browser.get(base + "/tests/" + test.path("id").asString()).json();
            if (scenarioKey.equals(detail.path("scenarioKey").asString())) {
                return detail;
            }
        }
        throw new AssertionError("No test " + scenarioKey);
    }

    private String environment() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Target " + UUID.randomUUID());
        body.put("baseUrl", TargetApi.BASE_URL);
        body.put("timeoutMs", 4000);
        body.put("followRedirects", false);
        body.put("executionPolicy", "ALLOW_DESTRUCTIVE");
        body.put("authorizationConfirmed", true);
        body.put("auth", Map.of("type", "API_KEY_HEADER", "headerName", "X-API-Key", "valueVariable", "API_KEY"));
        String id = browser.post(base + "/environments", body).json().path("id").asString();
        browser.put(base + "/environments/" + id + "/variables/API_KEY", Map.of("value", TargetApi.API_KEY, "secret", true));
        return id;
    }

    private JsonNode runAll(String environmentId) {
        String runId = browser.post(base + "/runs", Map.of("environmentId", environmentId)).json().path("id").asString();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JsonNode run = browser.get(base + "/runs/" + runId).json();
            if (run.path("status").asString().equals("COMPLETED")) {
                return run;
            }
            assertThat(System.currentTimeMillis()).as("run did not finish").isLessThan(deadline);
            sleep(100);
        }
    }

    private List<String> triggers() {
        return jdbc.queryForList("select trigger from coverage_snapshots where project_id = ? order by taken_at",
                String.class, UUID.fromString(projectId));
    }

    @Test
    void withoutASpecificationThereIsNothingToMeasure() {
        Response response = browser.get(base + "/coverage");

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("NO_ACTIVE_SPECIFICATION");
    }

    @Test
    void coverageFollowsTestsRunsAndSpecificationVersions() {
        importSpec(TargetApi.SPEC, "items.yaml");
        JsonNode beforeGeneration = coverage("");
        assertThat(metric(beforeGeneration, "operations", "value")).isEqualTo(4);
        assertThat(metric(beforeGeneration, "operationsWithTests", "value")).isZero();
        assertThat(beforeGeneration.path("gaps").path("operationsWithoutTests")).hasSize(4);

        generate();
        int generated = browser.get(base + "/tests/summary").json().path("total").asInt();
        JsonNode generatedCoverage = coverage("");
        assertThat(metric(generatedCoverage, "operationsWithTests", "value")).isEqualTo(4);
        assertThat(metric(generatedCoverage, "tests", "value")).isEqualTo(generated);
        assertThat(metric(generatedCoverage, "responsesTargeted", "total")).isEqualTo(13);
        assertThat(metric(generatedCoverage, "executed", "value")).isZero();
        assertThat(generatedCoverage.path("outcomes").path("notRun").asInt()).isEqualTo(generated);
        // Every constraint the generator identifies has a generated test.
        assertThat(metric(generatedCoverage, "constraintsCovered", "value"))
                .isEqualTo(metric(generatedCoverage, "constraintsCovered", "total")).isPositive();
        assertThat(triggers()).containsExactly("VERSION_ACTIVATED", "TESTS_GENERATED");

        // Disable DELETE: the operation is no longer covered.
        List<String> deleteTests = tests("DELETE /items/{id}").stream().map(test -> test.path("id").asString()).toList();
        assertThat(browser.post(base + "/tests/bulk", Map.of("testIds", deleteTests, "enabled", false)).status()).isEqualTo(200);
        // Turn "invalid credentials" of GET /items into a copy of the valid request: counted once.
        JsonNode valid = test("GET /items", "happy.minimal");
        JsonNode invalid = test("GET /items", "auth.invalid");
        Map<String, Object> copy = new LinkedHashMap<>();
        copy.put("name", "Copy of the valid request");
        copy.put("request", valid.path("request"));
        copy.put("expectations", Map.of("statuses", List.of("200"), "summary", "Same as the valid request."));
        copy.put("version", invalid.path("version").asLong());
        Response edited = browser.put(base + "/tests/" + invalid.path("id").asString(), copy);
        assertThat(edited.status()).as(edited.body()).isEqualTo(200);

        JsonNode curated = coverage("");
        int distinct = generated - deleteTests.size() - 1;
        assertThat(metric(curated, "operationsWithTests", "value")).isEqualTo(3);
        assertThat(curated.path("gaps").path("operationsWithoutTests").get(0).asString()).isEqualTo("DELETE /items/{id}");
        assertThat(metric(curated, "tests", "value")).isEqualTo(distinct);
        JsonNode items = rowOf(curated, "GET /items");
        assertThat(items.path("duplicates").asInt()).isEqualTo(1);
        // The copy replaced the only invalid-credentials test, but "no credentials" is still there.
        assertThat(items.path("missingNegatives")).isEmpty();

        // Run everything that is enabled.
        String environment = environment();
        JsonNode run = runAll(environment);
        awaitSnapshot("RUN_FINISHED");
        JsonNode executed = coverage("");
        assertThat(metric(executed, "executed", "value")).isEqualTo(distinct);
        assertThat(run.path("counts").path("total").asInt()).as("the copy ran too, but is counted once").isEqualTo(distinct + 1);
        assertThat(executed.path("outcomes").path("failed").asInt()).as("the documented maxLength deviation").isEqualTo(1);
        assertThat(metric(executed, "passRate", "value")).isEqualTo(distinct - 1);
        assertThat(metric(executed, "passRate", "total")).isEqualTo(distinct);
        // Received: GET /items 200, 401; POST /items 201, 400, 401, 415; GET /items/{id} 200, 400, 401. DELETE was not run.
        assertThat(metric(executed, "responsesExercised", "value")).isEqualTo(9);
        List<String> notExercised = new ArrayList<>();
        executed.path("gaps").path("responsesNotExercised").forEach(gap -> notExercised.add(
                gap.path("operationKey").asString() + " " + gap.path("status").asString() + " " + gap.path("targeted").asBoolean()));
        assertThat(notExercised).containsExactly("GET /items/{id} 404 true", "DELETE /items/{id} 204 false",
                "DELETE /items/{id} 400 false", "DELETE /items/{id} 401 false");

        // Drill-down of one operation.
        JsonNode post = browser.get(base + "/coverage/operation?key=POST%20/items").json();
        assertThat(post.path("responses").findValuesAsString("status")).containsExactly("201", "400", "401", "415");
        assertThat(post.path("responses").get(0).path("receivedStatuses").get(0).asInt()).isEqualTo(201);
        assertThat(post.path("tests")).hasSize(tests("POST /items").size());
        assertThat(post.path("negatives").findValuesAsString("state")).doesNotContain("MISSING");
        assertThat(browser.get(base + "/coverage/operation?key=GET%20/nothing").status()).isEqualTo(404);

        // Another environment has no runs yet: coverage stays, execution is empty.
        JsonNode other = coverage("?environmentId=" + environment());
        assertThat(metric(other, "operationsWithTests", "value")).isEqualTo(3);
        assertThat(metric(other, "executed", "value")).isZero();
        assertThat(metric(other, "responsesExercised", "value")).isZero();
        assertThat(coverage("?environmentId=" + environment).path("outcomes").path("executed").asInt()).isEqualTo(distinct);
        assertThat(browser.get(base + "/coverage?environmentId=" + UUID.randomUUID()).status()).isEqualTo(404);

        // A new version: DELETE removed, PUT added, POST gains a response.
        String v2 = TargetApi.SPEC
                .replace("""
                            delete:
                              responses:
                                '204': {description: Deleted}
                                '400': {description: Invalid id}
                                '401': {description: Missing or wrong key}
                        """, """
                            put:
                              requestBody:
                                required: true
                                content:
                                  application/json:
                                    schema: {$ref: '#/components/schemas/NewItem'}
                              responses:
                                '200': {description: Replaced}
                                '401': {description: Missing or wrong key}
                        """)
                .replace("""
                                '415': {description: Not JSON}
                        """, """
                                '415': {description: Not JSON}
                                '409': {description: Duplicate name}
                        """);
        assertThat(v2).contains("put:").contains("'409'").doesNotContain("delete:");
        importSpec(v2, "items-v2.yaml");

        JsonNode changes = browser.get(base + "/coverage/changes").json();
        assertThat(changes.path("from").path("number").asInt()).isEqualTo(1);
        assertThat(changes.path("to").path("number").asInt()).isEqualTo(2);
        assertThat(changes.path("operations")).extracting(change -> change.path("kind").asString() + " "
                + change.path("operationKey").asString()).containsExactly("ADDED PUT /items/{id}",
                        "REMOVED DELETE /items/{id}", "CHANGED POST /items");
        assertThat(changes.path("operations").get(1).path("staleTests").asInt()).isEqualTo(deleteTests.size());
        assertThat(changes.path("operations").get(2).path("aspects").get(0).asString()).isEqualTo("responses");
        assertThat(changes.path("operations").get(2).path("testsFromEarlierVersion").asInt()).isEqualTo(tests("POST /items").size());
        assertThat(changes.path("unchanged").asInt()).isEqualTo(2);
        assertThat(changes.path("baseline").path("trigger").asString()).isEqualTo("RUN_FINISHED");
        assertThat(changes.path("baseline").path("metrics").path("executed").asInt()).isEqualTo(distinct);
        assertThat(changes.path("current").path("operations").asInt()).isEqualTo(4);
        assertThat(changes.path("current").path("operationsWithTests").asInt()).as("PUT has no tests yet").isEqualTo(3);
        assertThat(changes.path("current").path("executed").asInt()).as("runs on version 1 do not count for version 2")
                .isZero();
        assertThat(changes.path("history").get(0).path("trigger").asString()).isEqualTo("VERSION_ACTIVATED");
        assertThat(changes.path("history").get(0).path("versionNumber").asInt()).isEqualTo(2);

        // Snapshots are history.
        assertThat(triggers()).containsExactly("VERSION_ACTIVATED", "TESTS_GENERATED", "RUN_FINISHED", "VERSION_ACTIVATED");
        UUID snapshotId = jdbc.queryForObject("select id from coverage_snapshots where project_id = ? limit 1", UUID.class,
                UUID.fromString(projectId));
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> jdbc.update(
                "update coverage_snapshots set metrics = '{}' where id = ?", snapshotId)))
                .hasMessageContaining("coverage_snapshots rows are immutable");
    }

    @Test
    void otherUsersSeeNothing() {
        importSpec(TargetApi.SPEC, "items.yaml");
        BrowserClient intruder = TestUsers.register(port).client();

        assertThat(intruder.get(base + "/coverage").status()).isEqualTo(404);
        assertThat(intruder.get(base + "/coverage/operation?key=GET%20/items").status()).isEqualTo(404);
        assertThat(intruder.get(base + "/coverage/changes").status()).isEqualTo(404);
    }

    private static JsonNode rowOf(JsonNode report, String operationKey) {
        for (JsonNode row : report.path("operations")) {
            if (row.path("operationKey").asString().equals(operationKey)) {
                return row;
            }
        }
        throw new AssertionError("No row " + operationKey);
    }

    private void awaitSnapshot(String trigger) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!triggers().contains(trigger)) {
            assertThat(System.currentTimeMillis()).as("no %s snapshot", trigger).isLessThan(deadline);
            sleep(100);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
