package com.apiforge.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.repository.AuditEventRepository;
import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestProjects;
import com.apiforge.support.TestUsers;
import com.apiforge.support.TestUsers.SignedInUser;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class TestGenerationIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditEventRepository auditEvents;

    SignedInUser owner;
    BrowserClient browser;
    String projectId;
    String tests;
    String specs;

    @BeforeEach
    void setUp() {
        owner = TestUsers.register(port);
        browser = owner.client();
        projectId = TestProjects.create(browser, "Generation " + UUID.randomUUID());
        tests = "/api/v1/projects/" + projectId + "/tests";
        specs = "/api/v1/projects/" + projectId + "/specs";
        Response imported = browser.postFile(specs, "file", "warehouse.yaml", fixture());
        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
    }

    private JsonNode generate() {
        Response response = browser.post(tests + "/generate", Map.of());
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json();
    }

    private JsonNode testsOf(String operationKey) {
        Response response = browser.get(tests + "?size=100&operationKey=" + encode(operationKey));
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().path("content");
    }

    /** The test of one scenario (scenario keys are unique per operation). */
    private JsonNode detail(String operationKey, String scenarioKey) {
        UUID id = jdbc.queryForObject("""
                select id from test_cases where project_id = ? and operation_key = ? and scenario_key = ?
                  and lifecycle_status <> 'ARCHIVED'""", UUID.class, UUID.fromString(projectId), operationKey, scenarioKey);
        return browser.get(tests + "/" + id).json();
    }

    private int liveTestCount() {
        return jdbc.queryForObject("select count(*) from test_cases where project_id = ? and lifecycle_status <> 'ARCHIVED'",
                Integer.class, UUID.fromString(projectId));
    }

    // ---- generation -----------------------------------------------------------------------------

    @Test
    void generationStoresExplainableTestsInTheGeneratedSuite() {
        JsonNode report = generate();

        int total = report.path("total").asInt();
        assertThat(report.path("operations").asInt()).isEqualTo(7);
        assertThat(report.path("specificationVersionNumber").asInt()).isEqualTo(1);
        assertThat(report.path("created").asInt()).isEqualTo(total).isGreaterThan(100);
        assertThat(report.path("generatorVersion").asString()).isEqualTo("2");
        assertThat(report.path("skipped").findValuesAsString("reason"))
                .anyMatch(reason -> reason.contains("changes the URL to a different route"))
                .anyMatch(reason -> reason.contains("oneOf/anyOf"));
        assertThat(liveTestCount()).isEqualTo(total);

        JsonNode suites = browser.get("/api/v1/projects/" + projectId + "/test-suites").json();
        assertThat(suites).hasSize(1);
        assertThat(suites.get(0).path("name").asString()).isEqualTo("Generated tests");
        assertThat(suites.get(0).path("kind").asString()).isEqualTo("GENERATED");
        assertThat(suites.get(0).path("testCount").asInt()).isEqualTo(total);

        JsonNode missingName = detail("POST /products", "body.missing:/name");
        assertThat(missingName.path("name").asString()).isEqualTo("Missing required field 'name'");
        assertThat(missingName.path("category").asString()).isEqualTo("MISSING_FIELD");
        assertThat(missingName.path("origin").asString()).isEqualTo("DETERMINISTIC");
        assertThat(missingName.path("reviewStatus").asString()).isEqualTo("APPROVED");
        assertThat(missingName.path("riskLevel").asString()).isEqualTo("MUTATING");
        assertThat(missingName.path("enabled").asBoolean()).isTrue();
        assertThat(missingName.path("executable").asBoolean()).isTrue();
        assertThat(missingName.path("rationale").asString())
                .isEqualTo("'name' is listed in 'required' of schema NewProduct. The request omits it.");
        assertThat(missingName.path("specLocation").asString()).isEqualTo("#/components/schemas/NewProduct/required");
        assertThat(missingName.path("generatorVersion").asString()).isEqualTo("2");
        assertThat(missingName.path("sourceSpecificationVersionId").asString())
                .isEqualTo(report.path("specificationVersionId").asString());
        JsonNode request = missingName.path("request");
        assertThat(request.path("body").path("mode").asString()).isEqualTo("JSON");
        assertThat(request.path("body").path("value").has("name")).isFalse();
        assertThat(request.path("body").path("value").has("sku")).isTrue();
        assertThat(request.path("auth").asString()).isEqualTo("ENVIRONMENT");
        JsonNode expectations = missingName.path("expectations");
        assertThat(expectations.path("statuses").valueStream().map(JsonNode::asString)).containsExactly("400", "422");
        assertThat(expectations.path("validateResponseSchema").asBoolean()).isTrue();
        assertThat(expectations.path("responseChecks").get(0).path("schemaName").asString()).isEqualTo("Error");

        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(owner.id(), AuditEventType.TESTS_GENERATED))
                .singleElement().satisfies(event -> assertThat(event.getDetails()).containsEntry("created", total));
    }

    @Test
    void regeneratingAnUnchangedSpecificationChangesNothing() {
        int total = generate().path("total").asInt();
        List<Map<String, Object>> before = jdbc.queryForList(
                "select id, dedupe_hash, version, request_template::text as request from test_cases where project_id = ? order by id",
                UUID.fromString(projectId));

        JsonNode second = generate();

        assertThat(second.path("created").asInt()).isZero();
        assertThat(second.path("updated").asInt()).isZero();
        assertThat(second.path("archived").asInt()).isZero();
        assertThat(second.path("unchanged").asInt()).isEqualTo(total);
        assertThat(jdbc.queryForList(
                "select id, dedupe_hash, version, request_template::text as request from test_cases where project_id = ? order by id",
                UUID.fromString(projectId))).isEqualTo(before);
    }

    @Test
    void generationCanBeLimitedToSelectedOperations() {
        JsonNode report = browser.post(tests + "/generate", Map.of("operationKeys", List.of("GET /health"))).json();

        assertThat(report.path("operations").asInt()).isEqualTo(1);
        assertThat(report.path("total").asInt()).isEqualTo(1);
        assertThat(testsOf("GET /health")).hasSize(1);

        Response unknown = browser.post(tests + "/generate", Map.of("operationKeys", List.of("GET /nope")));
        assertThat(unknown.status()).isEqualTo(400);
        assertThat(unknown.json().path("detail").asString()).contains("GET /nope");
    }

    @Test
    void generationRequiresAnActiveSpecification() {
        String empty = TestProjects.create(browser, "Empty " + UUID.randomUUID());

        Response response = browser.post("/api/v1/projects/" + empty + "/tests/generate", Map.of());

        assertThat(response.status()).isEqualTo(409);
        assertThat(response.code()).isEqualTo("NO_ACTIVE_SPECIFICATION");
    }

    @Test
    void generationPlanExplainsWhatIsAndIsNotGenerated() {
        Response response = browser.get(tests + "/generation-plan?operationKey=" + encode("POST /orders"));

        assertThat(response.status()).as(response.body()).isEqualTo(200);
        JsonNode plan = response.json();
        assertThat(plan.path("scenarios").findValuesAsString("scenarioKey")).contains("happy.minimal", "body.missing:/lines");
        assertThat(plan.path("skipped").findValuesAsString("scenario")).contains("Field scenarios inside field 'delivery'");
        assertThat(liveTestCount()).as("a plan stores nothing").isZero();
    }

    // ---- curation --------------------------------------------------------------------------------

    @Test
    void editedDisabledAndMovedTestsSurviveRegeneration() {
        generate();
        JsonNode edited = detail("POST /products", "body.missing:/name");
        JsonNode disabled = detail("POST /products", "body.missing:/sku");
        String customSuite = browser.post("/api/v1/projects/" + projectId + "/test-suites",
                Map.of("name", "Smoke")).json().path("id").asString();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pathParams", Map.of());
        request.put("query", Map.of());
        request.put("headers", Map.of("X-Debug", "1"));
        request.put("body", Map.of("mode", "JSON", "contentType", "application/json",
                "value", Map.of("sku", "ABC-1234", "price", 5, "category", "tools")));
        request.put("auth", "ENVIRONMENT");
        Response put = browser.put(tests + "/" + edited.path("id").asString(), Map.of(
                "name", "Name is mandatory (team rule)",
                "request", request,
                "expectations", Map.of("statuses", List.of("422"), "summary", "Our API answers 422."),
                "rationale", "Edited after the API review.",
                "version", edited.path("version").asLong()));
        assertThat(put.status()).as(put.body()).isEqualTo(200);
        assertThat(put.json().path("userModified").asBoolean()).isTrue();
        assertThat(put.json().path("expectations").path("responseChecks").findValuesAsString("status")).containsExactly("422");

        Response disable = browser.patch(tests + "/" + disabled.path("id").asString(),
                Map.of("enabled", false, "suiteId", customSuite, "version", disabled.path("version").asLong()));
        assertThat(disable.status()).as(disable.body()).isEqualTo(200);
        assertThat(disable.json().path("executable").asBoolean()).isFalse();

        JsonNode report = generate();

        assertThat(report.path("keptUserEdits").asInt()).isEqualTo(1);
        assertThat(report.path("created").asInt()).isZero();
        JsonNode afterEdit = browser.get(tests + "/" + edited.path("id").asString()).json();
        assertThat(afterEdit.path("name").asString()).isEqualTo("Name is mandatory (team rule)");
        assertThat(afterEdit.path("request").path("headers").path("X-Debug").asString()).isEqualTo("1");
        JsonNode afterDisable = browser.get(tests + "/" + disabled.path("id").asString()).json();
        assertThat(afterDisable.path("enabled").asBoolean()).isFalse();
        assertThat(afterDisable.path("suiteId").asString()).isEqualTo(customSuite);
    }

    @Test
    void changedSpecificationUpdatesTestsInPlaceAndArchivesObsoleteOnes() {
        generate();
        JsonNode tooLong = detail("POST /products", "body.too-long:/name");
        JsonNode activeType = detail("POST /products", "body.type:/active");
        assertThat(tooLong.path("name").asString()).isEqualTo("Field 'name' longer than maxLength 80");

        String changed = new String(fixture(), StandardCharsets.UTF_8)
                .replace("""
                        name:
                                  type: string
                                  minLength: 3
                                  maxLength: 80
                                  example: Cordless drill""", """
                        name:
                                  type: string
                                  minLength: 3
                                  maxLength: 60
                                  example: Cordless drill""")
                .replace("""
                                active:
                                  type: boolean
                                  default: true
                        """, "");
        assertThat(changed).doesNotContain("active:").contains("maxLength: 60");
        Response second = browser.postFile(specs + "?activate=true", "file", "warehouse-v2.yaml",
                changed.getBytes(StandardCharsets.UTF_8));
        assertThat(second.status()).as(second.body()).isEqualTo(201);

        JsonNode report = generate();

        assertThat(report.path("specificationVersionNumber").asInt()).isEqualTo(2);
        assertThat(report.path("updated").asInt()).isPositive();
        assertThat(report.path("archived").asInt()).isPositive();
        JsonNode updated = browser.get(tests + "/" + tooLong.path("id").asString()).json();
        assertThat(updated.path("name").asString()).isEqualTo("Field 'name' longer than maxLength 60");
        assertThat(updated.path("sourceSpecificationVersionId").asString())
                .isEqualTo(report.path("specificationVersionId").asString());
        assertThat(browser.get(tests + "/" + activeType.path("id").asString()).status())
                .as("archived tests are history, not part of the workspace").isEqualTo(404);
        assertThat(jdbc.queryForObject("select lifecycle_status from test_cases where id = ?", String.class,
                UUID.fromString(activeType.path("id").asString()))).isEqualTo("ARCHIVED");
        JsonNode archived = browser.get(tests + "?lifecycleStatus=ARCHIVED&size=100").json();
        assertThat(archived.path("content").findValuesAsString("id")).contains(activeType.path("id").asString());
    }

    @Test
    void listingFiltersAndSummaries() {
        int total = generate().path("total").asInt();

        JsonNode summary = browser.get(tests + "/summary").json();
        assertThat(summary.path("total").asInt()).isEqualTo(total);
        assertThat(summary.path("enabled").asInt()).isEqualTo(total);
        assertThat(summary.path("operations").findValuesAsString("operationKey")).hasSize(7).contains("GET /health");

        JsonNode firstPage = browser.get(tests + "?size=10").json();
        assertThat(firstPage.path("content")).hasSize(10);
        assertThat(firstPage.path("page").path("totalElements").asInt()).isEqualTo(total);

        JsonNode boundaries = browser.get(tests + "?size=100&category=BOUNDARY_VALUE").json().path("content");
        assertThat(boundaries.findValuesAsString("category")).isNotEmpty().containsOnly("BOUNDARY_VALUE");

        JsonNode orders = testsOf("POST /orders");
        assertThat(orders.findValuesAsString("operationKey")).containsOnly("POST /orders");
        assertThat(orders.get(0).path("name").asString()).as("ordered by sortOrder")
                .isEqualTo("Valid request (required fields only)");

        JsonNode search = browser.get(tests + "?q=" + encode("MAXLENGTH 80")).json().path("content");
        assertThat(search.findValuesAsString("name")).isNotEmpty().allMatch(name -> name.contains("maxLength 80"));

        assertThat(browser.get(tests + "?sort=rationale").status()).isEqualTo(400);
        assertThat(browser.get(tests + "?category=NOPE").status()).isEqualTo(400);
    }

    @Test
    void bulkChangesAndReviewDecisions() {
        generate();
        List<String> ids = new ArrayList<>(testsOf("GET /products").findValuesAsString("id"));
        List<String> three = ids.subList(0, 3);

        Response bulk = browser.post(tests + "/bulk", Map.of("testIds", three, "enabled", false));
        assertThat(bulk.status()).as(bulk.body()).isEqualTo(200);
        assertThat(bulk.json().path("updated").asInt()).isEqualTo(3);
        assertThat(browser.get(tests + "?size=100&enabled=false").json().path("content").findValuesAsString("id"))
                .containsExactlyInAnyOrderElementsOf(three);

        Response foreign = browser.post(tests + "/bulk", Map.of("testIds", List.of(ids.get(0), UUID.randomUUID()),
                "enabled", true));
        assertThat(foreign.status()).isEqualTo(404);
        assertThat(browser.post(tests + "/bulk", Map.of("testIds", three)).status()).isEqualTo(400);

        String reviewed = ids.get(3);
        Response reject = browser.post(tests + "/" + reviewed + "/review", Map.of("decision", "REJECT"));
        assertThat(reject.json().path("reviewStatus").asString()).isEqualTo("REJECTED");
        assertThat(reject.json().path("executable").asBoolean()).isFalse();
        assertThat(reject.json().path("reviewedBy").asString()).isEqualTo(owner.id().toString());
        Response approve = browser.post(tests + "/" + reviewed + "/review", Map.of("decision", "APPROVE"));
        assertThat(approve.json().path("reviewStatus").asString()).isEqualTo("APPROVED");
    }

    @Test
    void editsAreValidated() {
        generate();
        JsonNode test = detail("GET /products/{productId}", "happy.minimal");
        String url = tests + "/" + test.path("id").asString();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("pathParams", Map.of("wrong", 1));
        request.put("query", Map.of());
        request.put("headers", Map.of("Authorization", "Bearer secret"));
        request.put("body", Map.of("mode", "NONE"));
        request.put("auth", "ENVIRONMENT");

        Response invalid = browser.put(url, Map.of("name", "x", "request", request,
                "expectations", Map.of("statuses", List.of("299x")), "version", test.path("version").asLong()));

        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(invalid.json().path("errors").findValuesAsString("field")).containsExactlyInAnyOrder(
                "request.pathParams", "request.headers.Authorization", "expectations.statuses");

        request.put("pathParams", Map.of("productId", 1));
        request.put("headers", Map.of());
        Response stale = browser.put(url, Map.of("name", "x", "request", request,
                "expectations", Map.of("statuses", List.of("200")), "version", test.path("version").asLong() + 5));
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("STALE_VERSION");

        Response unknownField = browser.put(url, Map.of("name", "x", "request", request, "script", "alert(1)",
                "expectations", Map.of("statuses", List.of("200")), "version", test.path("version").asLong()));
        assertThat(unknownField.status()).as("tests cannot carry code").isEqualTo(400);
    }

    @Test
    void suitesOrganizeTestsAndNeverLoseThem() {
        generate();
        String suites = "/api/v1/projects/" + projectId + "/test-suites";
        JsonNode generated = browser.get(suites).json().get(0);
        Response created = browser.post(suites, Map.of("name", "Regression", "description", "Before releases"));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String regression = created.json().path("id").asString();

        assertThat(browser.post(suites, Map.of("name", "regression")).code()).isEqualTo("DUPLICATE_NAME");

        String testId = testsOf("GET /health").get(0).path("id").asString();
        assertThat(browser.post(tests + "/bulk", Map.of("testIds", List.of(testId), "suiteId", regression)).status())
                .isEqualTo(200);
        assertThat(browser.get(tests + "?suiteId=" + regression).json().path("content").findValuesAsString("id"))
                .containsExactly(testId);

        Response nonEmpty = browser.delete(suites + "/" + regression);
        assertThat(nonEmpty.status()).isEqualTo(409);
        assertThat(nonEmpty.json().path("detail").asString()).contains("still contains 1 test");
        assertThat(browser.delete(suites + "/" + generated.path("id").asString()).status()).isEqualTo(409);

        Response renamed = browser.patch(suites + "/" + regression,
                Map.of("name", "Release checks", "version", created.json().path("version").asLong()));
        assertThat(renamed.json().path("name").asString()).isEqualTo("Release checks");
        assertThat(renamed.json().path("testCount").asInt()).isEqualTo(1);

        browser.post(tests + "/bulk", Map.of("testIds", List.of(testId), "suiteId", generated.path("id").asString()));
        assertThat(browser.delete(suites + "/" + regression).status()).isEqualTo(204);
        assertThat(browser.get(tests + "/" + testId).status()).isEqualTo(200);
    }

    @Test
    void otherUsersCannotSeeOrChangeTheTests() {
        generate();
        String testId = testsOf("GET /health").get(0).path("id").asString();
        BrowserClient intruder = TestUsers.register(port).client();

        assertThat(intruder.get(tests).status()).isEqualTo(404);
        assertThat(intruder.get(tests + "/" + testId).status()).isEqualTo(404);
        assertThat(intruder.get(tests + "/summary").status()).isEqualTo(404);
        assertThat(intruder.patch(tests + "/" + testId, Map.of("enabled", false, "version", 0)).status()).isEqualTo(404);
        assertThat(intruder.post(tests + "/generate", Map.of()).status()).isEqualTo(404);
        assertThat(intruder.get("/api/v1/projects/" + projectId + "/test-suites").status()).isEqualTo(404);
        assertThat(browser.get(tests + "/" + testId).json().path("enabled").asBoolean()).isTrue();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static byte[] fixture() {
        try (InputStream stream = TestGenerationIntegrationTest.class.getResourceAsStream("/specs/warehouse-lab-3.0.yaml")) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture");
            }
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }
}
