package com.apiforge.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.FakeOllama;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TargetApi;
import com.apiforge.support.TestProjects;
import com.apiforge.support.TestUsers;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The AI features against a scripted Ollama ({@link FakeOllama}): structured output, validation of
 * suggestions, every failure mode, graceful degradation, and what reaches the prompt and the audit.
 */
@IntegrationTest
class AiIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    BrowserClient browser;
    String projectId;
    String base;

    @BeforeAll
    static void startServers() {
        TargetApi.start();
    }

    @BeforeEach
    void setUp() {
        FakeOllama.start();
        FakeOllama.reset();
        browser = TestUsers.register(port).client();
        projectId = TestProjects.create(browser, "AI " + UUID.randomUUID());
        base = "/api/v1/projects/" + projectId;
    }

    @AfterEach
    void restartFake() {
        FakeOllama.start();
    }

    private void importAndGenerate() {
        assertThat(browser.postFile(base + "/specs", "file", "items.yaml", TargetApi.SPEC.getBytes(StandardCharsets.UTF_8))
                .status()).isEqualTo(201);
        assertThat(browser.post(base + "/tests/generate", Map.of()).status()).isEqualTo(200);
    }

    private JsonNode status() {
        Response response = browser.get("/api/v1/ai/status?refresh=true");
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private JsonNode test(String operationKey, String scenarioKey) {
        for (JsonNode test : browser.get(base + "/tests?size=100&operationKey=" + operationKey.replace(" ", "%20")).json()
                .path("content")) {
            JsonNode detail = browser.get(base + "/tests/" + test.path("id").asString()).json();
            if (scenarioKey.equals(detail.path("scenarioKey").asString())) {
                return detail;
            }
        }
        throw new AssertionError(scenarioKey);
    }

    private static Map<String, Object> suggestion(String name, Map<String, Object> body, Map<String, String> headers,
            List<String> statuses, String duplicateOf, String duplicateReason) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pathParams", Map.of());
        request.put("query", Map.of());
        request.put("headers", headers);
        request.put("body", body);
        request.put("auth", "ENVIRONMENT");
        Map<String, Object> suggestion = new LinkedHashMap<>();
        suggestion.put("name", name);
        suggestion.put("category", "STRING_LENGTH");
        suggestion.put("rationale", "Checks a case the generated tests do not cover. More detail follows here.");
        suggestion.put("request", request);
        suggestion.put("expectedStatuses", statuses);
        suggestion.put("duplicateOf", duplicateOf);
        suggestion.put("duplicateReason", duplicateReason);
        return suggestion;
    }

    private Response suggest() {
        return browser.post(base + "/ai/suggestions", Map.of("operationKey", "POST /items"));
    }

    private static String reply(List<Map<String, Object>> suggestions) {
        return JSON.writeValueAsString(Map.of("suggestions", suggestions));
    }

    private static String userPrompt(JsonNode request) {
        return request.path("messages").get(1).path("content").asString();
    }

    // --- Availability ---------------------------------------------------------------------------

    @Test
    void statusReportsReadyMissingModelAndUnavailable() {
        JsonNode ready = status();
        assertThat(ready.path("state").asString()).isEqualTo("READY");
        assertThat(ready.path("model").asString()).isEqualTo(FakeOllama.MODEL);
        assertThat(ready.path("server").asString()).isEqualTo("http://127.0.0.1:18092");

        FakeOllama.installed(List.of("other:7b"));
        JsonNode missing = status();
        assertThat(missing.path("state").asString()).isEqualTo("MODEL_MISSING");
        assertThat(missing.path("message").asString()).contains("ollama pull " + FakeOllama.MODEL);

        FakeOllama.stop();
        assertThat(status().path("state").asString()).isEqualTo("UNAVAILABLE");
    }

    // --- Suggestions ----------------------------------------------------------------------------

    @Test
    void suggestionsAreValidatedDeduplicatedAndStoredPendingReview() {
        importAndGenerate();
        JsonNode valid = test("POST /items", "happy.minimal");
        Map<String, Object> validBody = Map.of("mode", "JSON", "value", JSON.convertValue(valid.path("request").path("body")
                .path("value"), Object.class));
        Map<String, Object> blank = Map.of("mode", "JSON", "value", Map.of("name", "   "));
        FakeOllama.answer(FakeOllama.content(reply(List.of(
                suggestion("Name made of spaces only", blank, Map.of(), List.of("400"), null, null),
                suggestion("Valid item again", validBody, Map.of(), List.of("201"), null, null),
                suggestion("Credentials in a header", blank, Map.of("Authorization", "Bearer abc"), List.of("400"), null, null),
                suggestion("Create the same item twice", validBody, Map.of(), List.of("201"), "Valid request",
                        "Sent again to see that creating an identical item is still accepted."),
                suggestion("Spaces again", blank, Map.of(), List.of("400"), null, null)))));

        Response response = suggest();

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode report = response.json();
        assertThat(report.path("model").asString()).isEqualTo(FakeOllama.MODEL);
        assertThat(report.path("created")).extracting(test -> test.path("name").asString())
                .containsExactly("Name made of spaces only", "Create the same item twice");
        assertThat(report.path("created")).allSatisfy(test -> {
            assertThat(test.path("origin").asString()).isEqualTo("AI_SUGGESTED");
            assertThat(test.path("reviewStatus").asString()).isEqualTo("PENDING_REVIEW");
            assertThat(test.path("executable").asBoolean()).isFalse();
            assertThat(test.path("aiModel").asString()).isEqualTo(FakeOllama.MODEL);
        });
        assertThat(report.path("created").get(1).path("rationale").asString())
                .contains("Overlaps with “" + valid.path("name").asString() + "”");
        List<String> discarded = new ArrayList<>();
        report.path("discarded").forEach(item -> discarded.add(item.path("name").asString() + ": " + item.path("reason").asString()));
        assertThat(discarded).hasSize(3);
        assertThat(discarded.get(0)).startsWith("Valid item again: Sends the same request");
        assertThat(discarded.get(1)).startsWith("Credentials in a header: Not a valid test").contains("Authorization");
        assertThat(discarded.get(2)).isEqualTo("Spaces again: Same as the suggestion “Name made of spaces only”.");

        // The request carried the strict schema and the bounded options; the prompt held no secrets.
        JsonNode sent = FakeOllama.requests().getFirst();
        assertThat(sent.path("format").path("required").get(0).asString()).isEqualTo("suggestions");
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("options").path("num_predict").asInt()).isPositive();
        assertThat(userPrompt(sent)).contains("existingTests").contains("generatorCouldNotBuild")
                .doesNotContain(TargetApi.API_KEY).doesNotContain(TargetApi.BASE_URL);

        // Pending suggestions never run; approving one makes it runnable.
        String suggestionId = report.path("created").get(0).path("id").asString();
        String environment = environment();
        JsonNode run = runAll(environment);
        assertThat(jdbc.queryForObject("select count(*) from test_results where test_run_id = ? and test_case_id = ?",
                Integer.class, UUID.fromString(run.path("id").asString()), UUID.fromString(suggestionId))).isZero();
        assertThat(browser.post(base + "/tests/" + suggestionId + "/review", Map.of("decision", "APPROVE")).json()
                .path("executable").asBoolean()).isTrue();

        // Audited with sizes and timing, never with prompt content.
        Map<String, Object> audit = jdbc.queryForMap("""
                select details::text as details from audit_events
                where project_id = ? and event_type = 'AI_SUGGESTIONS_REQUESTED'""", UUID.fromString(projectId));
        assertThat(String.valueOf(audit.get("details"))).contains("\"outcome\": \"OK\"").contains("promptSha256")
                .contains("\"created\": 2").doesNotContain("existingTests").doesNotContain("Name made of spaces");
    }

    @Test
    void malformedOutputIsRetriedOnceThenReported() {
        importAndGenerate();
        FakeOllama.answer(FakeOllama.content("Sure! Here are some tests: {not json"),
                FakeOllama.content("{\"suggestions\": [{\"name\": \"x\"}]}"));

        Response response = suggest();

        assertThat(response.status()).isEqualTo(502);
        assertThat(response.code()).isEqualTo("AI_INVALID_OUTPUT");
        assertThat(response.json().path("detail").asString()).contains("does not match the required structure");
        assertThat(FakeOllama.requests()).hasSize(2);
        assertThat(jdbc.queryForObject("select count(*) from test_cases where project_id = ? and origin = 'AI_SUGGESTED'",
                Integer.class, UUID.fromString(projectId))).isZero();
        assertThat(jdbc.queryForObject("""
                select details->>'outcome' from audit_events where project_id = ? and event_type = 'AI_SUGGESTIONS_REQUESTED'""",
                String.class, UUID.fromString(projectId))).isEqualTo("INVALID_OUTPUT");
    }

    @Test
    void aValidSecondAttemptIsUsed() {
        importAndGenerate();
        FakeOllama.answer(FakeOllama.content("not json at all"), FakeOllama.content(reply(List.of())));

        Response response = suggest();

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        assertThat(response.json().path("attempts").asInt()).isEqualTo(2);
    }

    @Test
    void missingModelTimeoutsAndOverloadAreReportedWithoutEndlessRetries() {
        importAndGenerate();

        FakeOllama.answer(FakeOllama.error(404, "model \"test-model:1b\" not found, try pulling it first"));
        Response missing = suggest();
        assertThat(missing.status()).isEqualTo(503);
        assertThat(missing.code()).isEqualTo("AI_MODEL_NOT_FOUND");
        assertThat(FakeOllama.requests()).as("not retried").hasSize(1);

        FakeOllama.reset();
        FakeOllama.answer(new FakeOllama.Reply(200, "{}", 4_000));
        Response slow = suggest();
        assertThat(slow.status()).isEqualTo(504);
        assertThat(slow.code()).isEqualTo("AI_TIMEOUT");
        assertThat(FakeOllama.requests()).as("not retried").hasSize(1);

        FakeOllama.reset();
        FakeOllama.answer(FakeOllama.error(503, "server busy, please try again. maximum pending requests exceeded"),
                FakeOllama.error(503, "server busy, please try again. maximum pending requests exceeded"));
        Response busy = suggest();
        assertThat(busy.status()).isEqualTo(503);
        assertThat(busy.code()).isEqualTo("AI_BUSY");
        assertThat(FakeOllama.requests()).as("one bounded retry").hasSize(2);
    }

    @Test
    void withoutOllamaEverythingElseStillWorks() {
        FakeOllama.stop();
        importAndGenerate();

        Response suggestion = suggest();
        assertThat(suggestion.status()).isEqualTo(503);
        assertThat(suggestion.code()).isEqualTo("AI_UNAVAILABLE");

        JsonNode run = runAll(environment());
        assertThat(run.path("status").asString()).isEqualTo("COMPLETED");
        assertThat(run.path("counts").path("failed").asInt()).as("the documented deviation").isEqualTo(1);
        assertThat(browser.get(base + "/coverage").status()).isEqualTo(200);
    }

    // --- Explanations ---------------------------------------------------------------------------

    @Test
    void failuresAreExplainedWithFactsCheckedAgainstTheData() {
        importAndGenerate();
        JsonNode run = runAll(environment());
        String runId = run.path("id").asString();
        JsonNode failed = browser.get(base + "/runs/" + runId + "/results?outcome=FAILED").json().path("content").get(0);
        JsonNode passed = browser.get(base + "/runs/" + runId + "/results?outcome=PASSED").json().path("content").get(0);
        String explanationUrl = base + "/runs/" + runId + "/results/" + failed.path("id").asString() + "/explanation";
        assertThat(browser.get(explanationUrl).status()).isEqualTo(404);
        FakeOllama.answer(FakeOllama.content(JSON.writeValueAsString(Map.of(
                "summary", "The API accepted a name longer than the documented maxLength.",
                "observedFacts", List.of(
                        Map.of("statement", "The test expected status 400 but the response was 201.", "source", "ASSERTION"),
                        Map.of("statement", "The schema check failed at $.name.", "source", "ASSERTION"),
                        Map.of("statement", "The element $.items[9] is missing.", "source", "RESPONSE"),
                        Map.of("statement", "The request created an item.", "source", "REQUEST")),
                "likelyCauses", List.of(Map.of("hypothesis",
                        "I reviewed the source code: validation is missing in ItemController.java:42.", "confidence", "MEDIUM",
                        "evidence", List.of("status 201", "$.name"))),
                "debuggingSteps", List.of("Send the same request and check whether the name is validated."),
                "suggestedChanges", List.of(Map.of("target", "API_IMPLEMENTATION", "change", "Enforce maxLength 20."))))));

        Response explained = browser.post(explanationUrl, Map.of());

        assertThat(explained.status()).as(explained.body()).isEqualTo(200);
        JsonNode explanation = explained.json();
        assertThat(explanation.path("observedFacts")).extracting(fact -> fact.path("grounding").asString())
                .containsExactly("VERIFIED", "VERIFIED", "UNVERIFIED", "NOT_CHECKABLE");
        assertThat(explanation.path("likelyCauses").get(0).path("evidence")).extracting(item -> item.path("grounding").asString())
                .containsExactly("VERIFIED", "VERIFIED");
        assertThat(explanation.path("sourceCodeProvided").asBoolean()).isFalse();
        assertThat(explanation.path("warnings")).extracting(JsonNode::asString).anySatisfy(warning ->
                assertThat(warning).contains("claims or implies access to source code"));
        assertThat(explanation.path("warnings")).extracting(JsonNode::asString).anySatisfy(warning ->
                assertThat(warning).contains("1 observed fact cites a value not found"));
        assertThat(browser.get(explanationUrl).json().path("id").asString()).isEqualTo(explanation.path("id").asString());

        String prompt = userPrompt(FakeOllama.requests().getFirst());
        assertThat(prompt).contains("failedAssertions").contains("documentedResponses").contains("\"status\":201")
                .doesNotContain(TargetApi.API_KEY).doesNotContain("127.0.0.1:18090").doesNotContain("X-API-Key");

        Response notFailed = browser.post(base + "/runs/" + runId + "/results/" + passed.path("id").asString() + "/explanation",
                Map.of());
        assertThat(notFailed.status()).isEqualTo(409);
        assertThat(TestUsers.register(port).client().post(explanationUrl, Map.of()).status()).isEqualTo(404);
    }

    // --- Helpers --------------------------------------------------------------------------------

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
            assertThat(System.currentTimeMillis()).isLessThan(deadline);
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
