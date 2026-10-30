package com.apiforge.execution;

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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
 * The execution engine end to end against {@link TargetApi}, an authorized local test API:
 * success, HTTP errors, timeouts, malformed and oversized responses, blocked destinations,
 * redirect restrictions, secret redaction, runs, cancellation and limits.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
class ExecutionIntegrationTest {

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
        projectId = TestProjects.create(browser, "Execution " + UUID.randomUUID());
        base = "/api/v1/projects/" + projectId;
    }

    private String environment(String policy, boolean followRedirects, int timeoutMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Target " + UUID.randomUUID());
        body.put("baseUrl", TargetApi.BASE_URL);
        body.put("timeoutMs", timeoutMs);
        body.put("followRedirects", followRedirects);
        body.put("executionPolicy", policy);
        body.put("authorizationConfirmed", true);
        body.put("auth", Map.of("type", "API_KEY_HEADER", "headerName", "X-API-Key", "valueVariable", "API_KEY"));
        Response created = browser.post(base + "/environments", body);
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String id = created.json().path("id").asString();
        browser.put(base + "/environments/" + id + "/variables/API_KEY", Map.of("value", TargetApi.API_KEY, "secret", true));
        return id;
    }

    private String readOnlyEnvironment() {
        return environment("READ_ONLY", false, 2000);
    }

    private static Map<String, Object> request(Map<String, String> headers, Map<String, Object> query, Map<String, Object> body) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pathParams", Map.of());
        request.put("query", query);
        request.put("headers", headers);
        request.put("body", body);
        request.put("auth", "ENVIRONMENT");
        return request;
    }

    private Response adHoc(String environmentId, String method, String path, Map<String, Object> request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environmentId", environmentId);
        body.put("method", method);
        body.put("path", path);
        body.put("request", request);
        return browser.post(base + "/executions", body);
    }

    private JsonNode get(String environmentId, String path) {
        Response response = adHoc(environmentId, "GET", path, request(Map.of(), Map.of(), Map.of("mode", "NONE")));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private static List<String> checks(JsonNode result, String result_) {
        List<String> names = new ArrayList<>();
        result.path("checks").forEach(check -> {
            if (check.path("result").asString().equals(result_)) {
                names.add(check.path("name").asString());
            }
        });
        return names;
    }

    // ---- single requests -----------------------------------------------------------------------

    @Test
    void successfulRequestIsRecordedWithStatusHeadersBodyDurationAndCorrelationId() {
        JsonNode result = get(readOnlyEnvironment(), "/items");

        assertThat(result.path("outcome").asString()).isEqualTo("PASSED");
        assertThat(result.path("response").path("status").asInt()).isEqualTo(200);
        assertThat(result.path("response").path("body").asString()).isEqualTo("[{\"id\":1,\"name\":\"drill\"}]");
        assertThat(result.path("response").path("contentType").asString()).isEqualTo("application/json");
        assertThat(result.path("durationMs").asInt()).isNotNegative();
        assertThat(result.path("timeToHeadersMs").asInt()).isBetween(0, result.path("durationMs").asInt());
        assertThat(checks(result, "PASS")).containsExactly("STATUS", "JSON_SYNTAX");
        String correlationId = result.path("correlationId").asString();
        assertThat(correlationId).isEqualTo(TargetApi.lastCorrelationId("/items"));
        assertThat(result.path("request").path("correlationId").asString()).isEqualTo(correlationId);
        assertThat(result.path("request").path("url").asString()).isEqualTo(TargetApi.BASE_URL + "/items");
        JsonNode run = browser.get(base + "/runs").json().path("content").get(0);
        assertThat(run.path("kind").asString()).isEqualTo("AD_HOC");
        assertThat(run.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.path("counts").path("passed").asInt()).isEqualTo(1);
    }

    @Test
    void secretsNeverReachResponsesHistoryAuditOrLogs(CapturedOutput output) {
        String environment = readOnlyEnvironment();
        Response sent = adHoc(environment, "GET", "/echo",
                request(Map.of("X-Debug", "token={{API_KEY}}"), Map.of("q", "{{API_KEY}}"), Map.of("mode", "NONE")));

        assertThat(sent.status()).as(sent.body()).isEqualTo(200);
        JsonNode result = sent.json();
        // The target echoed the API key back in its body; it was really sent...
        assertThat(result.path("response").path("body").asString()).containsIgnoringCase("X-API-Key: «redacted»");
        // ...but no stored or returned form contains it.
        assertThat(sent.body()).doesNotContain(TargetApi.API_KEY);
        assertThat(result.path("request").path("headers").path("X-API-Key").asString()).isEqualTo("«redacted»");
        assertThat(result.path("request").path("headers").path("X-Debug").asString()).isEqualTo("token=«redacted»");
        assertThat(result.path("request").path("url").asString()).isEqualTo(TargetApi.BASE_URL + "/echo?q=«redacted»");
        assertThat(result.path("response").path("headers").path("Set-cookie").asString()).isEqualTo("«redacted»");
        String detail = browser.get(base + "/runs/" + result.path("testRunId").asString() + "/results/"
                + result.path("id").asString()).body();
        assertThat(detail).doesNotContain(TargetApi.API_KEY);
        assertThat(jdbc.queryForObject("""
                select count(*) from test_results where request_snapshot::text like ? or response_body like ?
                   or response_headers::text like ? or coalesce(error_message, '') like ?""", Integer.class,
                "%" + TargetApi.API_KEY + "%", "%" + TargetApi.API_KEY + "%", "%" + TargetApi.API_KEY + "%",
                "%" + TargetApi.API_KEY + "%")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_events where details::text like ?", Integer.class,
                "%" + TargetApi.API_KEY + "%")).isZero();
        assertThat(output.getAll()).doesNotContain(TargetApi.API_KEY).contains("outbound GET 127.0.0.1:18090/echo -> 200");
    }

    @Test
    void httpErrorsAreFailedResultsWithTheirResponse() {
        JsonNode result = get(readOnlyEnvironment(), "/status/500");

        assertThat(result.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(result.path("response").path("status").asInt()).isEqualTo(500);
        assertThat(checks(result, "FAIL")).containsExactly("STATUS");
        assertThat(result.path("errorKind").isNull()).isTrue();
    }

    @Test
    void timeoutsEndAsErrorsWithAClearReason() {
        JsonNode result = get(environment("READ_ONLY", false, 500), "/slow");

        assertThat(result.path("outcome").asString()).isEqualTo("ERROR");
        assertThat(result.path("errorKind").asString()).isEqualTo("TIMEOUT");
        assertThat(result.path("errorMessage").asString()).contains("read timeout of 500 ms");
        assertThat(result.path("response").isNull()).isTrue();
    }

    @Test
    void malformedJsonFailsTheSyntaxCheck() {
        JsonNode result = get(readOnlyEnvironment(), "/malformed");

        assertThat(result.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(checks(result, "FAIL")).containsExactly("JSON_SYNTAX");
        assertThat(result.path("response").path("body").asString()).isEqualTo("{\"items\": [1, 2,");
    }

    @Test
    void oversizedAndBinaryBodiesAreNotStoredInFull() {
        String environment = readOnlyEnvironment();
        JsonNode large = get(environment, "/large");
        JsonNode binary = get(environment, "/binary");

        assertThat(large.path("response").path("truncated").asBoolean()).isTrue();
        assertThat(large.path("response").path("body").asString()).hasSize(16 * 1024);
        assertThat(large.path("response").path("sizeBytes").asLong()).isEqualTo(200 * 1024);
        assertThat(binary.path("response").path("body").isNull()).isTrue();
        assertThat(binary.path("response").path("sizeBytes").asLong()).isEqualTo(4);
    }

    @Test
    void redirectsAreOnlyFollowedWhenEnabledAndNeverToBlockedDestinations() {
        JsonNode notFollowed = get(readOnlyEnvironment(), "/redirect-local");
        String following = environment("READ_ONLY", true, 2000);
        JsonNode followed = get(following, "/redirect-local");
        JsonNode metadata = get(following, "/redirect-metadata");

        assertThat(notFollowed.path("response").path("status").asInt()).isEqualTo(302);
        assertThat(notFollowed.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(followed.path("response").path("status").asInt()).isEqualTo(200);
        assertThat(followed.path("request").path("redirects").get(0).asString()).isEqualTo(TargetApi.BASE_URL + "/echo");
        assertThat(metadata.path("outcome").asString()).isEqualTo("ERROR");
        assertThat(metadata.path("errorKind").asString()).isEqualTo("REDIRECT_BLOCKED");
        assertThat(metadata.path("errorMessage").asString()).contains("link-local or cloud metadata address");
    }

    @Test
    void destinationsThatBecameUnsafeAreBlockedAtExecutionAndAudited() {
        String environment = readOnlyEnvironment();
        for (String unsafe : List.of("http://169.254.169.254", "http://127.0.0.1:18099", "http://localhost:18099")) {
            // Saved values were checked when saved; execution checks again (DNS, trust and policy may change).
            jdbc.update("update environments set base_url = ? where id = ?", unsafe, UUID.fromString(environment));

            JsonNode result = get(environment, "/items");

            assertThat(result.path("outcome").asString()).as(unsafe).isEqualTo("ERROR");
            assertThat(result.path("errorKind").asString()).as(unsafe).isEqualTo("BLOCKED_BY_POLICY");
        }
        assertThat(jdbc.queryForObject("select count(*) from audit_events where project_id = ? and event_type = 'OUTBOUND_BLOCKED'",
                Integer.class, UUID.fromString(projectId))).isEqualTo(3);
    }

    @Test
    void unresolvedVariablesAreReportedWithoutSending() {
        int before = TargetApi.hits("/items");
        JsonNode result = adHoc(readOnlyEnvironment(), "GET", "/items",
                request(Map.of("X-Tenant", "{{TENANT}}"), Map.of(), Map.of("mode", "NONE"))).json();

        assertThat(result.path("outcome").asString()).isEqualTo("ERROR");
        assertThat(result.path("errorKind").asString()).isEqualTo("VARIABLE_UNRESOLVED");
        assertThat(result.path("errorMessage").asString()).contains("'TENANT'");
        assertThat(TargetApi.hits("/items")).isEqualTo(before);
    }

    @Test
    void executionPolicyAndRequestRulesAreEnforced() {
        String readOnly = readOnlyEnvironment();
        Map<String, Object> json = Map.of("mode", "JSON", "contentType", "application/json", "value", Map.of("name", "x"));

        assertThat(adHoc(readOnly, "POST", "/items", request(Map.of(), Map.of(), json)).code()).isEqualTo("EXECUTION_POLICY");
        assertThat(adHoc(readOnly, "TRACE", "/items", request(Map.of(), Map.of(), Map.of("mode", "NONE"))).status())
                .isEqualTo(400);
        assertThat(adHoc(readOnly, "GET", "/items?x=1", request(Map.of(), Map.of(), Map.of("mode", "NONE"))).status())
                .isEqualTo(400);
        assertThat(adHoc(readOnly, "GET", "//evil.example/x", request(Map.of(), Map.of(), Map.of("mode", "NONE"))).status())
                .isEqualTo(400);
        assertThat(adHoc(readOnly, "GET", "/items", request(Map.of("Host", "evil.example"), Map.of(), Map.of("mode", "NONE")))
                .status()).isEqualTo(400);
    }

    // ---- runs of generated tests ---------------------------------------------------------------

    private void importAndGenerate() {
        Response imported = browser.postFile(base + "/specs", "file", "items.yaml",
                TargetApi.SPEC.getBytes(StandardCharsets.UTF_8));
        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
        assertThat(browser.post(base + "/tests/generate", Map.of()).status()).isEqualTo(200);
    }

    private JsonNode awaitRun(String runId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (true) {
            JsonNode run = browser.get(base + "/runs/" + runId).json();
            if (List.of("COMPLETED", "CANCELLED", "INTERRUPTED").contains(run.path("status").asString())) {
                return run;
            }
            assertThat(System.currentTimeMillis()).as("run %s did not finish", runId).isLessThan(deadline);
            sleep(100);
        }
    }

    private List<JsonNode> results(String runId) {
        List<JsonNode> results = new ArrayList<>();
        browser.get(base + "/runs/" + runId + "/results?size=100").json().path("content").forEach(results::add);
        return results;
    }

    @Test
    void generatedTestsRunAgainstTheTargetWithExplainableOutcomes() {
        importAndGenerate();
        int generated = browser.get(base + "/tests/summary").json().path("total").asInt();

        Response started = browser.post(base + "/runs", Map.of("environmentId", environment("ALLOW_DESTRUCTIVE", false, 2000)));

        assertThat(started.status()).as(started.body()).isEqualTo(202);
        JsonNode run = awaitRun(started.json().path("id").asString());
        assertThat(run.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.path("counts").path("total").asInt()).isEqualTo(generated);
        assertThat(run.path("counts").path("error").asInt()).isZero();
        assertThat(run.path("counts").path("skipped").asInt()).isZero();
        List<JsonNode> results = results(run.path("id").asString());
        assertThat(run.path("counts").path("failed").asInt()).as("the documented maxLength deviation; failed: %s",
                results.stream().filter(result -> result.path("outcome").asString().equals("FAILED"))
                        .map(result -> result.path("testName").asString() + " -> " + result.path("firstFailure").asString())
                        .toList()).isEqualTo(1);
        assertThat(run.path("counts").path("passed").asInt()).isEqualTo(generated - 1);
        assertThat(results).filteredOn(result -> result.path("outcome").asString().equals("FAILED"))
                .singleElement().satisfies(failed -> {
                    assertThat(failed.path("testName").asString()).isEqualTo("Field 'name' longer than maxLength 20");
                    assertThat(failed.path("responseStatus").asInt()).isEqualTo(201);
                });
        assertThat(results).filteredOn(result -> result.path("testName").asString().equals("Request without credentials"))
                .allSatisfy(result -> assertThat(result.path("responseStatus").asInt()).isEqualTo(401));
    }

    @Test
    void readOnlyEnvironmentsSkipMutatingTestsWithAReason() {
        importAndGenerate();

        JsonNode run = awaitRun(browser.post(base + "/runs", Map.of("environmentId", readOnlyEnvironment())).json()
                .path("id").asString());

        List<JsonNode> skipped = results(run.path("id").asString()).stream()
                .filter(result -> result.path("outcome").asString().equals("SKIPPED")).toList();
        assertThat(skipped).isNotEmpty().allSatisfy(result -> {
            assertThat(result.path("operationKey").asString()).matches("(POST|DELETE) .*");
            assertThat(result.path("errorMessage").asString()).contains("policy READ_ONLY");
        });
        assertThat(run.path("counts").path("skipped").asInt()).isEqualTo(skipped.size());
    }

    // ---- contract validation and reporting -----------------------------------------------------

    private JsonNode variant(String environment, String variant) {
        Response response = adHoc(environment, "GET", "/items", request(Map.of(), Map.of("variant", variant),
                Map.of("mode", "NONE")));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private static List<String> summary(JsonNode result) {
        List<String> checks = new ArrayList<>();
        result.path("checks").forEach(check -> checks.add(check.path("name").asString() + "=" + check.path("result").asString()));
        return checks;
    }

    @Test
    void responsesAreValidatedAgainstTheActiveContract() {
        importAndGenerate();
        String environment = readOnlyEnvironment();

        JsonNode valid = get(environment, "/items");
        JsonNode legacy = variant(environment, "legacy");
        JsonNode xml = variant(environment, "xml");
        JsonNode teapot = variant(environment, "teapot");
        JsonNode unknown = get(environment, "/status/500");

        assertThat(valid.path("outcome").asString()).isEqualTo("PASSED");
        assertThat(summary(valid)).containsExactly("STATUS=PASS", "STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=PASS",
                "JSON_SYNTAX=PASS", "BODY_SCHEMA=PASS");

        assertThat(legacy.path("outcome").asString()).isEqualTo("FAILED");
        List<String> violations = new ArrayList<>();
        legacy.path("checks").forEach(check -> {
            if (check.path("result").asString().equals("FAIL")) {
                violations.add(String.join(" | ", check.path("name").asString(), check.path("path").asString(),
                        check.path("keyword").asString(), check.path("expected").asString(), check.path("actual").asString()));
            }
        });
        assertThat(violations).containsExactlyInAnyOrder(
                "BODY_SCHEMA | $[0].id | type | integer | string \"1\"",
                "BODY_SCHEMA | $[0].name | minLength | at least 1 character | 0 characters");

        assertThat(xml.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(summary(xml)).containsExactly("STATUS=PASS", "STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=FAIL", "BODY_SCHEMA=SKIPPED");

        assertThat(teapot.path("outcome").asString()).isEqualTo("FAILED");
        assertThat(summary(teapot)).containsExactly("STATUS=FAIL", "STATUS_DOCUMENTED=FAIL", "JSON_SYNTAX=PASS",
                "BODY_SCHEMA=SKIPPED");
        assertThat(teapot.path("checks").get(1).path("expected").asString()).isEqualTo("one of 200, 401");

        // Not in the specification: the status expectation still decides, the contract is reported as skipped.
        assertThat(summary(unknown)).containsExactly("STATUS=FAIL", "STATUS_DOCUMENTED=SKIPPED", "JSON_SYNTAX=PASS");
    }

    @Test
    void skippedValidationsNeverCountAsPassedChecks() {
        importAndGenerate();

        JsonNode result = adHoc(readOnlyEnvironment(), "GET", "/items/abc",
                request(Map.of(), Map.of(), Map.of("mode", "NONE"))).json();

        // 400 is documented without a body: the body is reported as not validated rather than as valid.
        assertThat(result.path("response").path("status").asInt()).isEqualTo(400);
        assertThat(summary(result)).containsExactly("STATUS=FAIL", "STATUS_DOCUMENTED=PASS", "CONTENT_TYPE=SKIPPED",
                "JSON_SYNTAX=PASS", "BODY_SCHEMA=SKIPPED");
        assertThat(result.path("checks").get(4).path("message").asString()).contains("was not validated");
    }

    @Test
    void resultsCanBeFilteredByOutcomeAndRunsCompared() {
        importAndGenerate();
        String before = awaitRun(browser.post(base + "/runs", Map.of("environmentId", readOnlyEnvironment())).json()
                .path("id").asString()).path("id").asString();
        assertThat(browser.get(base + "/runs/" + before + "/comparison").status()).as("no earlier run").isEqualTo(404);
        String after = awaitRun(browser.post(base + "/runs", Map.of("environmentId",
                environment("ALLOW_DESTRUCTIVE", false, 2000))).json().path("id").asString()).path("id").asString();

        JsonNode failed = browser.get(base + "/runs/" + after + "/results?outcome=FAILED").json();
        assertThat(failed.path("page").path("totalElements").asInt()).as(failed.toString()).isEqualTo(1);
        JsonNode deviation = failed.path("content").get(0);
        // The unexpected 201 also returns the over-long name, which breaks maxLength 20 of the documented Item.
        assertThat(deviation.path("failedChecks").asInt()).as(deviation.toString()).isEqualTo(2);
        assertThat(deviation.path("skippedChecks").asInt()).isZero();
        JsonNode deviationChecks = browser.get(base + "/runs/" + after + "/results/" + deviation.path("id").asString())
                .json().path("checks");
        assertThat(deviationChecks.get(4).path("path").asString()).isEqualTo("$.name");
        assertThat(deviationChecks.get(4).path("keyword").asString()).isEqualTo("maxLength");
        assertThat(deviation.path("firstFailure").asString()).contains("201");

        Response compared = browser.get(base + "/runs/" + after + "/comparison");
        assertThat(compared.status()).as(compared.body()).isEqualTo(200);
        JsonNode comparison = compared.json();
        int skippedBefore = (int) results(before).stream()
                .filter(result -> result.path("outcome").asString().equals("SKIPPED")).count();
        assertThat(comparison.path("baseline").path("id").asString()).isEqualTo(before);
        assertThat(comparison.path("counts").path("FIXED").asInt()).isEqualTo(skippedBefore - 1);
        assertThat(comparison.path("counts").path("CHANGED").asInt()).isEqualTo(1);
        assertThat(comparison.path("counts").path("REGRESSED").asInt()).isZero();
        assertThat(comparison.path("counts").path("NEW").asInt()).isZero();
        assertThat(comparison.path("changes").get(0).path("kind").asString()).isEqualTo("FIXED");
        JsonNode explicit = browser.get(base + "/runs/" + before + "/comparison?baseline=" + after).json();
        assertThat(explicit.path("counts").path("REGRESSED").asInt()).isEqualTo(skippedBefore - 1);
        assertThat(TestUsers.register(port).client().get(base + "/runs/" + after + "/comparison").status()).isEqualTo(404);
    }

    /** A test whose request hits the slow item lookup (3 s). */
    private String slowTest() {
        String id = jdbc.queryForObject("""
                select id::text from test_cases where project_id = ? and operation_key = 'GET /items/{id}'
                  and scenario_key = 'happy.minimal'""", String.class, UUID.fromString(projectId));
        JsonNode test = browser.get(base + "/tests/" + id).json();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pathParams", Map.of("id", 7777));
        request.put("query", Map.of());
        request.put("headers", Map.of());
        request.put("body", Map.of("mode", "NONE"));
        request.put("auth", "ENVIRONMENT");
        Response edited = browser.put(base + "/tests/" + id, Map.of("name", "Slow lookup", "request", request,
                "expectations", Map.of("statuses", List.of("404")), "version", test.path("version").asLong()));
        assertThat(edited.status()).as(edited.body()).isEqualTo(200);
        return id;
    }

    @Test
    void cancellingARunAbortsTheRequestInFlightAndSkipsTheRest() {
        importAndGenerate();
        String slow = slowTest();
        String other = jdbc.queryForObject("""
                select id::text from test_cases where project_id = ? and operation_key = 'GET /items/{id}'
                  and scenario_key = 'auth.none'""", String.class, UUID.fromString(projectId));
        // Runs execute in workspace order: the slow lookup (happy.minimal) comes before auth.none.
        String runId = browser.post(base + "/runs", Map.of("environmentId", readOnlyEnvironment(),
                "testIds", List.of(slow, other))).json().path("id").asString();

        sleep(500);
        Response cancel = browser.post(base + "/runs/" + runId + "/cancel", Map.of());

        assertThat(cancel.status()).as(cancel.body()).isEqualTo(202);
        JsonNode run = awaitRun(runId);
        assertThat(run.path("status").asString()).isEqualTo("CANCELLED");
        assertThat(run.path("statusReason").asString()).contains("Cancelled by the user");
        assertThat(results(runId)).extracting(result -> result.path("outcome").asString() + "/"
                + result.path("errorKind").asString()).containsExactlyInAnyOrder("ERROR/CANCELLED", "SKIPPED/");
        assertThat(browser.post(base + "/runs/" + runId + "/cancel", Map.of()).code()).isEqualTo("CONFLICT");
    }

    @Test
    void concurrentExecutionsPerUserAreLimited() {
        importAndGenerate();
        String slow = slowTest();
        String environment = readOnlyEnvironment();
        List<String> runs = new ArrayList<>();
        for (int index = 0; index < 2; index++) {
            Response started = browser.post(base + "/runs", Map.of("environmentId", environment, "testIds", List.of(slow)));
            assertThat(started.status()).isEqualTo(202);
            runs.add(started.json().path("id").asString());
        }

        Response third = browser.post(base + "/runs", Map.of("environmentId", environment, "testIds", List.of(slow)));
        Response adHoc = adHoc(environment, "GET", "/items", request(Map.of(), Map.of(), Map.of("mode", "NONE")));

        assertThat(third.status()).isEqualTo(429);
        assertThat(third.code()).isEqualTo("RATE_LIMITED");
        assertThat(adHoc.status()).isEqualTo(429);
        runs.forEach(runId -> browser.post(base + "/runs/" + runId + "/cancel", Map.of()));
        runs.forEach(this::awaitRun);
        assertThat(browser.post(base + "/runs", Map.of("environmentId", environment, "testIds", List.of(slow))).status())
                .as("slots are released when runs end").isEqualTo(202);
    }

    @Test
    void otherUsersCannotExecuteInOrCancelForeignProjects() {
        String environment = readOnlyEnvironment();
        JsonNode result = get(environment, "/items");
        BrowserClient intruder = TestUsers.register(port).client();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("environmentId", environment);
        body.put("method", "GET");
        body.put("path", "/items");
        body.put("request", request(Map.of(), Map.of(), Map.of("mode", "NONE")));

        assertThat(intruder.post(base + "/executions", body).status()).isEqualTo(404);
        assertThat(intruder.post(base + "/runs", Map.of("environmentId", environment)).status()).isEqualTo(404);
        assertThat(intruder.post(base + "/runs/" + result.path("testRunId").asString() + "/cancel", Map.of()).status())
                .isEqualTo(404);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
