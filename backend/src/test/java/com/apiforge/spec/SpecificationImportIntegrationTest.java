package com.apiforge.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class SpecificationImportIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditEventRepository auditEvents;

    SignedInUser owner;
    BrowserClient browser;
    String projectId;
    String specs;

    @BeforeEach
    void setUp() {
        owner = TestUsers.register(port);
        browser = owner.client();
        projectId = TestProjects.create(browser, "Specs " + UUID.randomUUID());
        specs = "/api/v1/projects/" + projectId + "/specs";
    }

    // ---- import ---------------------------------------------------------------------------------

    @Test
    void yamlFileUploadCreatesActiveVersionWithNormalizedOperations() {
        Response created = browser.postFile(specs, "file", "inventory.yaml", fixture("inventory-3.0.yaml"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        JsonNode version = created.json().path("version");
        String versionId = version.path("id").asString();
        assertThat(created.header("Location")).contains(specs + "/" + versionId);
        assertThat(version.path("versionNumber").asInt()).isEqualTo(1);
        assertThat(version.path("sourceFormat").asString()).isEqualTo("YAML");
        assertThat(version.path("sourceName").asString()).isEqualTo("inventory.yaml");
        assertThat(version.path("operationCount").asInt()).isEqualTo(5);
        assertThat(version.path("active").asBoolean()).isTrue();
        assertThat(codes(created.json().path("messages"))).contains("UNSUPPORTED_FEATURE");
        assertThat(browser.get("/api/v1/projects/" + projectId).json().path("activeSpecificationVersionId").asString())
                .isEqualTo(versionId);

        JsonNode detail = browser.get(specs + "/" + versionId).json();
        assertThat(detail.path("summary").path("title").asString()).isEqualTo("Inventory API");
        assertThat(detail.path("servers").get(0).path("url").asString()).isEqualTo("https://inventory.example.test/api");
        assertThat(detail.path("securitySchemes").has("bearerAuth")).isTrue();
        assertThat(detail.path("tags").valueStream().map(tag -> tag.path("name").asString()))
                .containsExactly("orders", "products");

        JsonNode operations = browser.get(specs + "/" + versionId + "/operations").json();
        assertThat(operations.valueStream().map(op -> op.path("operationKey").asString())).containsExactly(
                "GET /orders", "POST /orders", "GET /orders/{orderId}", "DELETE /orders/{orderId}",
                "PUT /products/{sku}/image");

        String listOrdersId = operations.get(0).path("id").asString();
        JsonNode listOrders = browser.get(specs + "/" + versionId + "/operations/" + listOrdersId).json();
        assertThat(listOrders.path("parameters").valueStream().map(p -> p.path("name").asString()))
                .containsExactly("pageSize", "status", "session");
        assertThat(listOrders.path("responses").get(0).path("content").path("application/json").path("schema")
                .path("items").path("$ref").asString()).isEqualTo("#/components/schemas/Order");

        JsonNode schemas = browser.get(specs + "/" + versionId + "/schemas").json();
        assertThat(schemas.path("Category").path("properties").path("parent").path("$ref").asString())
                .isEqualTo("#/components/schemas/Category");
        assertThat(schemas.path("OrderStatus").path("enum").valueStream().map(JsonNode::asString))
                .containsExactly("PENDING", "SHIPPED", "CANCELLED");
    }

    @Test
    void pastedJsonTextIsImported() {
        Response created = browser.post(specs, Map.of("content", text("pets-3.0.json"), "sourceName", "pasted"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        assertThat(created.json().path("version").path("sourceFormat").asString()).isEqualTo("JSON");
        assertThat(created.json().path("version").path("operationCount").asInt()).isEqualTo(3);
    }

    @Test
    void originalDocumentIsStoredByteForByte() {
        String original = text("inventory-3.0.yaml");
        String versionId = importText(original);

        Response raw = browser.get(specs + "/" + versionId + "/raw");

        assertThat(raw.status()).isEqualTo(200);
        assertThat(raw.body()).isEqualTo(original);
        assertThat(raw.header("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment").contains("specification-v1.yaml"));
        assertThat(raw.header("Content-Type")).hasValueSatisfying(value -> assertThat(value).startsWith("text/plain"));
    }

    @Test
    void officialPetstoreSampleImportsWithItsFullInventory() {
        Response created = browser.postFile(specs, "file", "petstore3-openapi.yaml", fixture("petstore3-official.yaml"));

        assertThat(created.status()).as(created.body()).isEqualTo(201);
        String versionId = created.json().path("version").path("id").asString();
        List<String> keys = browser.get(specs + "/" + versionId + "/operations").json().valueStream()
                .map(op -> op.path("operationKey").asString()).toList();
        assertThat(keys).hasSize(19).contains("GET /pet/{petId}", "POST /store/order", "GET /user/login");
    }

    // ---- rejected documents ------------------------------------------------------------------------

    @Test
    void missingRequiredFieldsAreReportedAndNothingIsStored() {
        Response rejected = browser.post(specs, Map.of("content", "openapi: 3.0.3\npaths: {}\n"));

        assertThat(rejected.status()).isEqualTo(422);
        assertThat(rejected.code()).isEqualTo("SPEC_INVALID");
        assertThat(rejected.json().path("messages").valueStream())
                .anySatisfy(message -> {
                    assertThat(message.path("severity").asString()).isEqualTo("ERROR");
                    assertThat(message.path("code").asString()).isEqualTo("MISSING_FIELD");
                    assertThat(message.path("message").asString()).contains("'info'");
                });
        assertThat(versionCount()).isZero();
        assertThat(browser.get("/api/v1/projects/" + projectId).json().path("activeSpecificationVersionId").isNull())
                .isTrue();
    }

    @Test
    void malformedYamlUploadReportsTheLine() {
        Response rejected = browser.postFile(specs, "file", "broken.yaml",
                "openapi: 3.0.3\ninfo:\n  title: [oops\n".getBytes(StandardCharsets.UTF_8));

        assertThat(rejected.status()).isEqualTo(422);
        JsonNode first = rejected.json().path("messages").get(0);
        assertThat(first.path("code").asString()).isEqualTo("PARSE_ERROR");
        assertThat(first.path("location").asString()).startsWith("line ");
    }

    @Test
    void externalReferenceIsRejectedWithoutAnyOutboundRequest() throws IOException {
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "type: string\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String target = "http://127.0.0.1:" + server.getAddress().getPort() + "/schemas.yaml#/Pet";
            String document = """
                    openapi: 3.0.3
                    info: {title: External, version: '1'}
                    paths:
                      /pets:
                        get:
                          responses:
                            '200':
                              description: OK
                              content:
                                application/json:
                                  schema:
                                    $ref: '%s'
                    """.formatted(target);

            Response imported = browser.post(specs, Map.of("content", document));
            Response validated = browser.post(specs + "/validate", Map.of("content", document));

            assertThat(imported.status()).isEqualTo(422);
            assertThat(codes(imported.json().path("messages"))).containsExactly("EXTERNAL_REFERENCE");
            assertThat(validated.json().path("valid").asBoolean()).isFalse();
            assertThat(hits).as("requests received by the referenced server").hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void oversizedUploadIsRejectedWith413() {
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        Arrays.fill(tooBig, (byte) 'a');

        Response upload = browser.postFile(specs, "file", "huge.yaml", tooBig);

        assertThat(upload.status()).isEqualTo(413);
        assertThat(upload.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(versionCount()).isZero();
    }

    @Test
    void oversizedPastedTextIsRejectedWith413() {
        // Above the 5 MB document limit but below the request cap: rejected by the service.
        Response justOver = browser.post(specs, Map.of("content", "a".repeat(5 * 1024 * 1024 + 10)));
        // Above the request cap: rejected by the size filter before the body is parsed.
        Response far = browser.post(specs, Map.of("content", "a".repeat(8 * 1024 * 1024)));

        assertThat(justOver.status()).isEqualTo(413);
        assertThat(justOver.code()).isEqualTo("PAYLOAD_TOO_LARGE");
        assertThat(far.status()).isEqualTo(413);
        assertThat(far.code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    // ---- dry run --------------------------------------------------------------------------------------

    @Test
    void validationIsADryRun() {
        Response valid = browser.postFile(specs + "/validate", "file", "pets.json", fixture("pets-3.0.json"));
        Response invalid = browser.post(specs + "/validate", Map.of("content", "openapi: 3.0.3\n"));

        assertThat(valid.status()).isEqualTo(200);
        assertThat(valid.json().path("valid").asBoolean()).isTrue();
        assertThat(valid.json().path("operationCount").asInt()).isEqualTo(3);
        assertThat(invalid.status()).isEqualTo(200);
        assertThat(invalid.json().path("valid").asBoolean()).isFalse();
        assertThat(codes(invalid.json().path("messages"))).contains("MISSING_FIELD");
        assertThat(versionCount()).isZero();
    }

    // ---- versions and history ---------------------------------------------------------------------------

    @Test
    void reimportCreatesNewVersionAndKeepsPreviousVersionWithItsTestHistory() {
        String v1 = importText(text("inventory-3.0.yaml"));
        UUID project = UUID.fromString(projectId);
        UUID suiteId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        jdbc.update("insert into test_suites (id, project_id, name, kind, created_at, updated_at) values (?, ?, 'S', 'CUSTOM', now(), now())",
                suiteId, project);
        jdbc.update("""
                insert into test_cases (id, project_id, suite_id, source_specification_version_id, operation_key, name,
                    category, origin, review_status, risk_level, request_template, expectations, dedupe_hash,
                    created_at, updated_at)
                values (?, ?, ?, ?, 'DELETE /orders/{orderId}', 'Cancel order', 'HAPPY_PATH', 'MANUAL', 'APPROVED',
                    'DESTRUCTIVE', '{}', '{}', ?, now(), now())""",
                testCaseId, project, suiteId, UUID.fromString(v1), "e".repeat(64));
        jdbc.update("insert into test_runs (id, project_id, specification_version_id, status, created_at) values (?, ?, ?, 'COMPLETED', now())",
                runId, project, UUID.fromString(v1));

        String v2 = importText(text("pets-3.0.json"));

        JsonNode versions = browser.get(specs).json().path("content");
        assertThat(versions.valueStream().map(v -> v.path("versionNumber").asInt())).containsExactly(2, 1);
        assertThat(versions.get(0).path("active").asBoolean()).isTrue();
        assertThat(versions.get(1).path("active").asBoolean()).isFalse();
        // The earlier version and its operations remain available...
        assertThat(browser.get(specs + "/" + v1 + "/operations").json().size()).isEqualTo(5);
        // ...the run still points at the contract it ran against...
        assertThat(jdbc.queryForObject("select specification_version_id from test_runs where id = ?", UUID.class, runId))
                .hasToString(v1);
        assertThat(browser.get("/api/v1/projects/" + projectId + "/runs/" + runId).json()
                .path("specificationVersionId").asString()).isEqualTo(v1);
        // ...and a test whose operation disappeared is kept but marked stale.
        assertThat(lifecycle(testCaseId)).isEqualTo("STALE");

        Response reactivated = browser.post(specs + "/" + v1 + "/activate", Map.of());
        assertThat(reactivated.status()).isEqualTo(200);
        assertThat(reactivated.json().path("active").asBoolean()).isTrue();
        assertThat(lifecycle(testCaseId)).isEqualTo("ACTIVE");
        assertThat(browser.get("/api/v1/projects/" + projectId).json().path("activeSpecificationVersionId").asString())
                .isEqualTo(v1);
        assertThat(v2).isNotEqualTo(v1);
    }

    @Test
    void identicalReimportIsFlagged() {
        importText(text("pets-3.0.json"));

        Response second = browser.post(specs, Map.of("content", text("pets-3.0.json")));

        assertThat(second.json().path("version").path("versionNumber").asInt()).isEqualTo(2);
        assertThat(codes(second.json().path("messages"))).contains("IDENTICAL_CONTENT");
    }

    @Test
    void importWithoutActivationKeepsTheCurrentVersion() {
        String v1 = importText(text("pets-3.0.json"));

        Response v2 = browser.post(specs + "?activate=false", Map.of("content", text("inventory-3.0.yaml")));

        assertThat(v2.json().path("version").path("active").asBoolean()).isFalse();
        assertThat(browser.get("/api/v1/projects/" + projectId).json().path("activeSpecificationVersionId").asString())
                .isEqualTo(v1);
    }

    @Test
    void importsAreAudited() {
        importText(text("pets-3.0.json"));

        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(owner.id(),
                AuditEventType.SPECIFICATION_IMPORTED)).singleElement()
                .satisfies(event -> assertThat(event.getDetails()).containsEntry("operationCount", 3));
    }

    // ---- access ------------------------------------------------------------------------------------------

    @Test
    void anotherUserCannotReadOrImportSpecifications() {
        String versionId = importText(text("pets-3.0.json"));
        String operationId = browser.get(specs + "/" + versionId + "/operations").json().get(0).path("id").asString();
        BrowserClient intruder = TestUsers.register(port).client();

        for (String path : List.of(specs, specs + "/" + versionId, specs + "/" + versionId + "/raw",
                specs + "/" + versionId + "/schemas", specs + "/" + versionId + "/operations",
                specs + "/" + versionId + "/operations/" + operationId)) {
            assertThat(intruder.get(path).status()).as(path).isEqualTo(404);
        }
        assertThat(intruder.post(specs, Map.of("content", text("pets-3.0.json"))).status()).isEqualTo(404);
        assertThat(intruder.post(specs + "/" + versionId + "/activate", Map.of()).status()).isEqualTo(404);
        assertThat(versionCount()).isEqualTo(1);
    }

    @Test
    void unknownVersionAndForeignOperationGive404() {
        String v1 = importText(text("pets-3.0.json"));
        String v2 = importText(text("inventory-3.0.yaml"));
        String operationOfV1 = browser.get(specs + "/" + v1 + "/operations").json().get(0).path("id").asString();

        assertThat(browser.get(specs + "/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(browser.get(specs + "/" + v2 + "/operations/" + operationOfV1).status()).isEqualTo(404);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private String importText(String content) {
        Response created = browser.post(specs, Map.of("content", content));
        assertThat(created.status()).as(created.body()).isEqualTo(201);
        return created.json().path("version").path("id").asString();
    }

    private int versionCount() {
        return jdbc.queryForObject("select count(*) from specification_versions where project_id = ?", Integer.class,
                UUID.fromString(projectId));
    }

    private String lifecycle(UUID testCaseId) {
        return jdbc.queryForObject("select lifecycle_status from test_cases where id = ?", String.class, testCaseId);
    }

    private static List<String> codes(JsonNode messages) {
        return messages.valueStream().map(message -> message.path("code").asString()).toList();
    }

    static byte[] fixture(String name) {
        try (InputStream stream = SpecificationImportIntegrationTest.class.getResourceAsStream("/specs/" + name)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static String text(String name) {
        return new String(fixture(name), StandardCharsets.UTF_8);
    }
}
