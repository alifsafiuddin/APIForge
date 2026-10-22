package com.apiforge.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
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

/** The dashboard and project overview count exactly what is stored; run history filters by kind, status and failures. */
@IntegrationTest
class DashboardIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void startTarget() {
        TargetApi.start();
    }

    @Test
    void newAccountHasAnEmptyDashboard() {
        BrowserClient browser = TestUsers.register(port).client();

        JsonNode dashboard = ok(browser.get("/api/v1/dashboard"));

        assertThat(dashboard.path("totals").properties()).allSatisfy(entry -> assertThat(entry.getValue().asInt())
                .as(entry.getKey()).isZero());
        assertThat(dashboard.path("projects")).isEmpty();
        assertThat(dashboard.path("recentRuns")).isEmpty();
    }

    @Test
    void dashboardAndOverviewReflectStoredProjectsTestsRunsAndCoverage() {
        BrowserClient browser = TestUsers.register(port).client();
        String empty = TestProjects.create(browser, "Empty " + UUID.randomUUID());
        String archived = TestProjects.create(browser, "Archived " + UUID.randomUUID());
        assertThat(browser.patch("/api/v1/projects/" + archived, Map.of("archived", true, "version", 0)).status())
                .isEqualTo(200);
        String projectName = "Items " + UUID.randomUUID();
        String projectId = TestProjects.create(browser, projectName);
        String base = "/api/v1/projects/" + projectId;
        Response imported = browser.postFile(base + "/specs?activate=true", "file", "items.yaml",
                TargetApi.SPEC.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
        assertThat(browser.post(base + "/tests/generate", Map.of()).status()).isEqualTo(200);
        int generated = ok(browser.get(base + "/tests/summary")).path("total").asInt();
        String environment = environment(browser, base);
        JsonNode run = runAll(browser, base, environment);
        // The snapshot taken when the run finished; later test changes do not rewrite it.
        JsonNode report = ok(browser.get(base + "/coverage"));
        // One test waits for review and one is stale (as AI suggestions and newer versions would leave them).
        List<UUID> tests = jdbc.queryForList("select id from test_cases where project_id = ? order by sort_order, id",
                UUID.class, UUID.fromString(projectId));
        jdbc.update("update test_cases set review_status = 'PENDING_REVIEW' where id = ?", tests.get(0));
        jdbc.update("update test_cases set lifecycle_status = 'STALE' where id = ?", tests.get(1));

        JsonNode dashboard = ok(browser.get("/api/v1/dashboard"));

        int failing = run.path("counts").path("failed").asInt() + run.path("counts").path("error").asInt() > 0 ? 1 : 0;
        JsonNode totals = dashboard.path("totals");
        assertThat(totals.path("activeProjects").asInt()).isEqualTo(2);
        assertThat(totals.path("archivedProjects").asInt()).isEqualTo(1);
        assertThat(totals.path("runsLast7Days").asInt()).isEqualTo(1);
        assertThat(totals.path("runsWithFailuresLast7Days").asInt()).isEqualTo(failing);
        assertThat(totals.path("runsInProgress").asInt()).isZero();
        assertThat(totals.path("testsPendingReview").asInt()).isEqualTo(1);
        assertThat(totals.path("staleTests").asInt()).isEqualTo(1);

        assertThat(dashboard.path("projects").valueStream().map(project -> project.path("id").asString()))
                .containsExactly(projectId, empty);
        JsonNode summary = dashboard.path("projects").get(0);
        assertThat(summary.path("name").asString()).isEqualTo(projectName);
        assertThat(summary.path("activeSpecification").path("versionNumber").asInt()).isEqualTo(1);
        assertThat(summary.path("activeSpecification").path("operations").asInt()).isEqualTo(4);
        assertThat(summary.path("tests").path("active").asInt()).isEqualTo(generated - 1);
        assertThat(summary.path("tests").path("pendingReview").asInt()).isEqualTo(1);
        assertThat(summary.path("tests").path("stale").asInt()).isEqualTo(1);
        assertThat(summary.path("tests").path("suites").asInt()).isPositive();
        assertThat(summary.path("environments").asInt()).isEqualTo(1);
        JsonNode lastRun = summary.path("lastRun");
        assertThat(lastRun.path("id").asString()).isEqualTo(run.path("id").asString());
        assertThat(lastRun.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(lastRun.path("total").asInt()).isEqualTo(run.path("counts").path("total").asInt());
        assertThat(lastRun.path("passed").asInt()).isEqualTo(run.path("counts").path("passed").asInt());
        assertThat(lastRun.path("failed").asInt()).isEqualTo(run.path("counts").path("failed").asInt());
        JsonNode coverage = summary.path("coverage");
        assertThat(coverage.path("operations").asInt()).isEqualTo(4);
        assertThat(coverage.path("operationsWithTests").asInt()).isEqualTo(4);
        assertThat(coverage.path("executed").asInt()).isEqualTo(metric(report, "executed"));
        assertThat(coverage.path("responsesExercised").asInt()).isEqualTo(metric(report, "responsesExercised"));

        JsonNode emptySummary = dashboard.path("projects").get(1);
        assertThat(emptySummary.path("activeSpecification").isNull()).isTrue();
        assertThat(emptySummary.path("lastRun").isNull()).isTrue();
        assertThat(emptySummary.path("coverage").isNull()).isTrue();
        assertThat(emptySummary.path("tests").path("active").asInt()).isZero();

        assertThat(dashboard.path("recentRuns")).singleElement().satisfies(recent -> {
            assertThat(recent.path("id").asString()).isEqualTo(run.path("id").asString());
            assertThat(recent.path("projectName").asString()).isEqualTo(projectName);
        });

        JsonNode overview = ok(browser.get(base + "/overview"));
        assertThat(overview.path("project")).isEqualTo(summary);
        assertThat(overview.path("recentRuns")).hasSize(1);
        JsonNode archivedOverview = ok(browser.get("/api/v1/projects/" + archived + "/overview"));
        assertThat(archivedOverview.path("project").path("archived").asBoolean()).isTrue();
    }

    @Test
    void overviewAndDashboardAreScopedToTheOwner() {
        BrowserClient owner = TestUsers.register(port).client();
        String projectId = TestProjects.create(owner, "Private " + UUID.randomUUID());
        BrowserClient intruder = TestUsers.register(port).client();

        assertThat(intruder.get("/api/v1/projects/" + projectId + "/overview").status()).isEqualTo(404);
        assertThat(ok(intruder.get("/api/v1/dashboard")).path("projects")).isEmpty();
        assertThat(new BrowserClient(port).get("/api/v1/dashboard").status()).isEqualTo(401);
    }

    @Test
    void runHistoryFiltersByKindStatusAndFailures() {
        BrowserClient browser = TestUsers.register(port).client();
        String projectId = TestProjects.create(browser, "Filters " + UUID.randomUUID());
        String base = "/api/v1/projects/" + projectId;
        assertThat(browser.postFile(base + "/specs?activate=true", "file", "items.yaml",
                TargetApi.SPEC.getBytes(StandardCharsets.UTF_8)).status()).isEqualTo(201);
        assertThat(browser.post(base + "/tests/generate", Map.of()).status()).isEqualTo(200);
        String environment = environment(browser, base);
        runAll(browser, base, environment);
        runAll(browser, base, environment);
        Map<String, Object> adHoc = new LinkedHashMap<>();
        adHoc.put("environmentId", environment);
        adHoc.put("method", "GET");
        adHoc.put("path", "/items");
        adHoc.put("request", Map.of("pathParams", Map.of(), "query", Map.of(), "headers", Map.of(),
                "body", Map.of("mode", "NONE"), "auth", "ENVIRONMENT"));
        assertThat(browser.post(base + "/executions", adHoc).status()).isEqualTo(200);

        List<JsonNode> all = runs(browser, base, "");
        assertThat(all).hasSize(3);
        assertThat(ids(runs(browser, base, "kind=AD_HOC"))).containsExactlyElementsOf(
                ids(all, run -> run.path("kind").asString().equals("AD_HOC"))).hasSize(1);
        assertThat(ids(runs(browser, base, "kind=TEST_RUN&status=COMPLETED"))).containsExactlyElementsOf(
                ids(all, run -> run.path("kind").asString().equals("TEST_RUN"))).hasSize(2);
        Predicate<JsonNode> hasFailures = run -> run.path("counts").path("failed").asInt() + run.path("counts").path("error").asInt() > 0;
        assertThat(ids(runs(browser, base, "failing=true"))).containsExactlyElementsOf(ids(all, hasFailures));
        assertThat(ids(runs(browser, base, "failing=false"))).containsExactlyElementsOf(ids(all, hasFailures.negate()
                .and(run -> run.path("status").asString().equals("COMPLETED"))));
        assertThat(runs(browser, base, "status=RUNNING")).isEmpty();
        assertThat(browser.get(base + "/runs?status=FINISHED").status()).isEqualTo(400);
    }

    private static JsonNode ok(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private static int metric(JsonNode report, String id) {
        for (JsonNode metric : report.path("metrics")) {
            if (metric.path("id").asString().equals(id)) {
                return metric.path("value").asInt();
            }
        }
        throw new AssertionError("No metric " + id);
    }

    private static List<JsonNode> runs(BrowserClient browser, String base, String query) {
        List<JsonNode> runs = new ArrayList<>();
        ok(browser.get(base + "/runs?size=50&" + query)).path("content").forEach(runs::add);
        return runs;
    }

    private static List<String> ids(List<JsonNode> runs) {
        return ids(runs, run -> true);
    }

    private static List<String> ids(List<JsonNode> runs, Predicate<JsonNode> filter) {
        return runs.stream().filter(filter).map(run -> run.path("id").asString()).toList();
    }

    private static String environment(BrowserClient browser, String base) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Target");
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

    private static JsonNode runAll(BrowserClient browser, String base, String environmentId) {
        String runId = browser.post(base + "/runs", Map.of("environmentId", environmentId)).json().path("id").asString();
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JsonNode run = ok(browser.get(base + "/runs/" + runId));
            if (run.path("status").asString().equals("COMPLETED")) {
                return run;
            }
            assertThat(System.currentTimeMillis()).as("run did not finish").isLessThan(deadline);
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
        }
    }
}
