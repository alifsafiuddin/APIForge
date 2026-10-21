package com.apiforge.spec.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import com.apiforge.config.ApiForgeProperties;
import com.apiforge.spec.domain.AnalyzedSpecification;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.ImportMessage;
import com.apiforge.spec.domain.ImportMessage.Code;
import com.apiforge.spec.domain.ImportMessage.Severity;
import com.apiforge.spec.domain.SpecificationRejectedException;
import com.apiforge.spec.domain.SpecificationVersion.SourceFormat;

class SpecificationAnalyzerTest {

    private final SpecificationAnalyzer analyzer = analyzer(2000);

    // ---- valid documents ---------------------------------------------------------------------

    @Test
    void validJsonSpecification() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("pets-3.0.json"));

        assertThat(spec.sourceFormat()).isEqualTo(SourceFormat.JSON);
        assertThat(spec.openapiVersion()).isEqualTo("3.0.3");
        assertThat(spec.title()).isEqualTo("Pets");
        assertThat(spec.apiVersion()).isEqualTo("1.2.0");
        assertThat(spec.servers()).extracting(server -> server.get("url")).containsExactly("https://pets.example.test/v1");
        assertThat(keys(spec)).containsExactly("GET /pets", "POST /pets", "GET /pets/{petId}");
        assertThat(spec.operations()).extracting(ApiOperation.Contents::operationId)
                .containsExactly("listPets", "createPet", "getPet");
        assertThat(spec.contentSha256()).matches("[0-9a-f]{64}");
        assertThat(spec.messages()).noneMatch(ImportMessage::isError);
    }

    @Test
    void responsesAreOrderedNumericallyWithDefaultLast() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("pets-3.0.json"));

        assertThat(operation(spec, "GET /pets/{petId}").responses()).extracting(response -> response.get("status"))
                .containsExactly("200", "404");
        assertThat(operation(spec, "GET /pets").responses()).extracting(response -> response.get("status"))
                .containsExactly("200", "default");
    }

    @Test
    void validYamlSpecificationWithNestedReferences() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("inventory-3.0.yaml"));

        assertThat(spec.sourceFormat()).isEqualTo(SourceFormat.YAML);
        assertThat(keys(spec)).containsExactly("GET /orders", "POST /orders", "GET /orders/{orderId}",
                "DELETE /orders/{orderId}", "PUT /products/{sku}/image");
        assertThat(spec.tags()).extracting(tag -> tag.get("name")).containsExactly("orders", "products");

        // Schema references are kept and resolvable against the stored component schemas.
        Map<String, Object> orderArray = schemaOf(operation(spec, "GET /orders").responses().getFirst());
        assertThat(orderArray).containsEntry("type", "array");
        assertThat(map(orderArray.get("items"))).containsEntry("$ref", "#/components/schemas/Order");
        assertThat(spec.componentSchemas()).containsKeys("Order", "OrderLine", "Product", "Category", "OrderStatus");

        // Recursive schema stays finite.
        Map<String, Object> category = map(spec.componentSchemas().get("Category"));
        assertThat(map(map(category.get("properties")).get("parent"))).containsEntry("$ref",
                "#/components/schemas/Category");
    }

    @Test
    void arraysEnumsNullableAndRequiredArePreserved() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("inventory-3.0.yaml"));
        Map<String, Object> newOrder = map(spec.componentSchemas().get("NewOrder"));
        Map<String, Object> properties = map(newOrder.get("properties"));

        assertThat(newOrder.get("required")).isEqualTo(List.of("lines"));
        assertThat(map(properties.get("lines"))).containsEntry("type", "array").containsEntry("minItems", 1);
        assertThat(map(map(properties.get("lines")).get("items"))).containsEntry("$ref",
                "#/components/schemas/OrderLine");
        assertThat(map(properties.get("note"))).containsEntry("nullable", true).containsEntry("maxLength", 500);
        assertThat(map(spec.componentSchemas().get("OrderStatus")).get("enum"))
                .isEqualTo(List.of("PENDING", "SHIPPED", "CANCELLED"));
        assertThat(map(spec.componentSchemas().get("Order")).get("allOf")).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(2);
    }

    @Test
    void referencedParametersRequestBodiesResponsesAndHeadersAreResolved() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("inventory-3.0.yaml"));
        ApiOperation.Contents listOrders = operation(spec, "GET /orders");

        assertThat(listOrders.parameters()).extracting(parameter -> parameter.get("name"))
                .containsExactly("pageSize", "status", "session");
        assertThat(listOrders.parameters().getFirst()).containsEntry("in", "query").doesNotContainKey("$ref");
        Map<String, Object> ok = listOrders.responses().getFirst();
        assertThat(map(map(ok.get("headers")).get("X-Total-Count"))).containsEntry("description",
                "Total number of items");
        assertThat(listOrders.responses().get(1)).containsEntry("status", "401")
                .containsEntry("description", "Authentication required");

        assertThat(operation(spec, "POST /orders").requestBody()).containsEntry("required", true);
        assertThat(operation(spec, "POST /orders").responses()).extracting(response -> response.get("status"))
                .containsExactly("201", "4XX");
    }

    @Test
    void pathLevelParametersAndSecurityAreEffectivePerOperation() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("inventory-3.0.yaml"));

        assertThat(operation(spec, "DELETE /orders/{orderId}").parameters()).extracting(parameter -> parameter.get("name"))
                .containsExactly("orderId");
        assertThat(operation(spec, "DELETE /orders/{orderId}").deprecated()).isTrue();
        // Inherits the global requirement...
        assertThat(operation(spec, "GET /orders").security()).containsExactly(Map.of("bearerAuth", List.of()));
        // ...unless explicitly disabled with "security: []".
        assertThat(operation(spec, "GET /orders/{orderId}").security()).isEmpty();
    }

    @Test
    void unsupportedFeaturesAreReportedPerOperationAndForTheDocument() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("inventory-3.0.yaml"));

        assertThat(messageCodes(operation(spec, "GET /orders").warnings())).contains("UNSUPPORTED_FEATURE");
        assertThat(operation(spec, "GET /orders").warnings()).anySatisfy(
                warning -> assertThat((String) warning.get("message")).contains("Cookie parameters"));
        assertThat(operation(spec, "POST /orders").warnings()).anySatisfy(
                warning -> assertThat((String) warning.get("message")).contains("Callbacks"));
        assertThat(operation(spec, "PUT /products/{sku}/image").warnings()).anySatisfy(
                warning -> assertThat((String) warning.get("message")).contains("multipart/form-data"));
        assertThat(spec.messages()).anySatisfy(message -> {
            assertThat(message.code()).isEqualTo(Code.UNSUPPORTED_FEATURE);
            assertThat(message.message()).contains("'oauth'");
        });
        assertThat(spec.messages()).noneMatch(ImportMessage::isError);
    }

    @Test
    void openApi31WithTypeArraysAndWebhooks() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("library-3.1.json"));

        assertThat(spec.openapiVersion()).isEqualTo("3.1.0");
        Map<String, Object> book = map(spec.componentSchemas().get("Book"));
        assertThat(map(map(book.get("properties")).get("subtitle")).get("type")).isEqualTo(List.of("string", "null"));
        assertThat(spec.messages()).extracting(ImportMessage::code)
                .contains(Code.UNSUPPORTED_FEATURE, Code.NO_SERVERS);
        assertThat(spec.messages()).anySatisfy(message -> assertThat(message.message()).contains("Webhooks"));
    }

    @Test
    void officialPetstoreSampleImports() {
        AnalyzedSpecification spec = analyzer.analyze(fixture("petstore3-official.yaml"));

        assertThat(spec.operations()).hasSize(19);
        assertThat(keys(spec)).contains("PUT /pet", "POST /pet", "GET /pet/findByStatus", "GET /pet/{petId}",
                "POST /pet/{petId}/uploadImage", "GET /store/inventory", "DELETE /user/{username}");
        assertThat(spec.messages()).noneMatch(ImportMessage::isError);
        assertThat(spec.messages()).anySatisfy(message -> assertThat(message.message()).contains("petstore_auth"));
        assertThat(operation(spec, "POST /pet/{petId}/uploadImage").warnings()).anySatisfy(
                warning -> assertThat((String) warning.get("message")).contains("application/octet-stream"));
    }

    // ---- malformed input -----------------------------------------------------------------------

    @Test
    void malformedJsonReportsLineAndColumn() {
        ImportMessage error = rejection("{\n  \"openapi\": \"3.0.3\",\n  \"info\": {\n}");

        assertThat(error.code()).isEqualTo(Code.PARSE_ERROR);
        assertThat(error.message()).startsWith("Invalid JSON");
        assertThat(error.location()).startsWith("line ");
    }

    @Test
    void malformedYamlReportsLineAndColumn() {
        ImportMessage error = rejection("openapi: 3.0.3\ninfo:\n  title: [unclosed\n  version: 1\n");

        assertThat(error.code()).isEqualTo(Code.PARSE_ERROR);
        assertThat(error.message()).startsWith("Invalid YAML");
        assertThat(error.location()).startsWith("line ");
    }

    @Test
    void emptyBinaryAndNonObjectDocumentsAreRejected() {
        assertThat(rejection("   \n ").code()).isEqualTo(Code.EMPTY_DOCUMENT);
        assertThat(rejection(new byte[] { (byte) 0xC3, (byte) 0x28 }).code()).isEqualTo(Code.INVALID_ENCODING);
        assertThat(rejection("openapi: 3.0.3\u0000").code()).isEqualTo(Code.INVALID_ENCODING);
        assertThat(rejection("[1, 2, 3]").code()).isEqualTo(Code.NOT_AN_OBJECT);
        assertThat(rejection("just a sentence").code()).isEqualTo(Code.NOT_AN_OBJECT);
    }

    @Test
    void utf8ByteOrderMarkIsAccepted() {
        byte[] withBom = concat(new byte[] { (byte) 0xEF, (byte) 0xBB, (byte) 0xBF }, fixture("pets-3.0.json"));

        assertThat(analyzer.analyze(withBom).operations()).hasSize(3);
    }

    // ---- versions and required fields ------------------------------------------------------------

    @Test
    void swagger2IsRejectedWithAConversionHint() {
        ImportMessage error = rejection("swagger: '2.0'\ninfo: {title: Old, version: '1'}\npaths: {}\n");

        assertThat(error.code()).isEqualTo(Code.UNSUPPORTED_VERSION);
        assertThat(error.message()).contains("Swagger 2.0").contains("Convert");
    }

    @ParameterizedTest
    @ValueSource(strings = { "3.2.0", "4.0.0", "3.0", "2.0" })
    void unsupportedOpenApiVersions(String version) {
        assertThat(rejection("openapi: '" + version + "'\ninfo: {title: X, version: '1'}\npaths: {}\n").code())
                .isEqualTo(Code.UNSUPPORTED_VERSION);
    }

    @Test
    void missingOpenApiField() {
        ImportMessage error = rejection("info: {title: X, version: '1'}\npaths: {}\n");

        assertThat(error.code()).isEqualTo(Code.MISSING_FIELD);
        assertThat(error.location()).isEqualTo("openapi");
    }

    @Test
    void missingRequiredOpenApiFieldsAreListed() {
        List<ImportMessage> messages = rejectionMessages("""
                openapi: 3.0.3
                paths:
                  /pets:
                    get:
                      operationId: listPets
                """);

        assertThat(messages).filteredOn(ImportMessage::isError)
                .extracting(ImportMessage::code, ImportMessage::message)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(Code.MISSING_FIELD, "Required field 'info' is missing."),
                        org.assertj.core.groups.Tuple.tuple(Code.MISSING_FIELD, "Required field 'responses' is missing."));
    }

    @Test
    void missingInfoTitleIsReported() {
        List<ImportMessage> messages = rejectionMessages("""
                openapi: 3.0.3
                info:
                  version: '1'
                paths: {}
                """);

        assertThat(messages).anySatisfy(message -> {
            assertThat(message.code()).isEqualTo(Code.MISSING_FIELD);
            assertThat(message.message()).contains("'title'");
        });
    }

    @Test
    void undeclaredPathParameterIsAnError() {
        List<ImportMessage> messages = rejectionMessages("""
                openapi: 3.0.3
                info: {title: X, version: '1'}
                paths:
                  /pets/{petId}:
                    get:
                      responses:
                        '200': {description: OK}
                """);

        assertThat(messages).anySatisfy(message -> assertThat(message.code()).isEqualTo(Code.UNDECLARED_PATH_PARAMETER));
    }

    // ---- references -------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "https://evil.example/schemas.yaml#/Pet",
            "http://169.254.169.254/latest/meta-data",
            "file:///etc/passwd",
            "./shared/pet.yaml#/Pet",
            "../pet.json",
            "//evil.example/pet.yaml",
            "pet.yaml"
    })
    void externalReferencesAreRejectedBeforeParsing(String ref) {
        ImportMessage error = rejection(withSchemaRef(ref));

        assertThat(error.code()).isEqualTo(Code.EXTERNAL_REFERENCE);
        assertThat(error.message()).contains(ref);
        assertThat(error.location()).contains("schema");
    }

    @Test
    void unresolvedLocalReferenceIsRejected() {
        ImportMessage error = rejection(withSchemaRef("#/components/schemas/Missing"));

        assertThat(error.code()).isEqualTo(Code.UNRESOLVED_REFERENCE);
        assertThat(error.message()).contains("#/components/schemas/Missing");
    }

    @Test
    void malformedReferencesAreRejected() {
        assertThat(rejection(withSchemaRef("#Pet")).code()).isEqualTo(Code.INVALID_REFERENCE);
        assertThat(rejection(withSchemaRefValue("42")).code()).isEqualTo(Code.INVALID_REFERENCE);
    }

    @Test
    void refKeysInsideExamplesAreDataNotReferences() {
        // inventory-3.0.yaml contains an example with a "$ref" key whose value is not a valid reference.
        assertThat(analyzer.analyze(fixture("inventory-3.0.yaml")).operations()).hasSize(5);
    }

    // ---- duplicates and ambiguity ------------------------------------------------------------------

    @Test
    void duplicateYamlKeysAreRejected() {
        ImportMessage error = rejection("""
                openapi: 3.0.3
                info: {title: X, version: '1'}
                paths:
                  /pets:
                    get:
                      responses: {'200': {description: first}}
                    get:
                      responses: {'200': {description: second}}
                """);

        assertThat(error.code()).isEqualTo(Code.DUPLICATE_KEY);
        assertThat(error.location()).startsWith("line ");
    }

    @Test
    void duplicateJsonKeysAreRejected() {
        ImportMessage error = rejection("""
                {"openapi": "3.0.3", "info": {"title": "X", "version": "1"},
                 "paths": {"/a": {"get": {"responses": {"200": {"description": "x"}}}},
                           "/a": {"post": {"responses": {"200": {"description": "y"}}}}}}
                """);

        assertThat(error.code()).isEqualTo(Code.DUPLICATE_KEY);
    }

    @Test
    void duplicateOperationIdsAndAmbiguousPathsAreRejected() {
        List<ImportMessage> messages = rejectionMessages("""
                openapi: 3.0.3
                info: {title: X, version: '1'}
                paths:
                  /pets/{id}:
                    parameters: [{name: id, in: path, required: true, schema: {type: string}}]
                    get: {operationId: getPet, responses: {'200': {description: OK}}}
                  /pets/{petId}:
                    parameters: [{name: petId, in: path, required: true, schema: {type: string}}]
                    delete: {operationId: getPet, responses: {'204': {description: Gone}}}
                """);

        assertThat(messages).extracting(ImportMessage::code)
                .contains(Code.DUPLICATE_OPERATION_ID, Code.AMBIGUOUS_PATH);
    }

    // ---- limits ------------------------------------------------------------------------------------

    @Test
    void yamlAliasBombIsRejected() {
        StringBuilder bomb = new StringBuilder("openapi: 3.0.3\nx-a: &a [x, x, x, x, x, x, x, x, x]\n");
        String previous = "a";
        for (int level = 0; level < 12; level++) {
            String name = "l" + level;
            bomb.append("x-").append(name).append(": &").append(name).append(" [")
                    .append(String.join(", ", java.util.Collections.nCopies(9, "*" + previous))).append("]\n");
            previous = name;
        }

        assertThat(rejection(bomb.toString()).code()).isEqualTo(Code.LIMIT_EXCEEDED);
    }

    @Test
    void yamlAnchorsAndAliasesAreExpandedNotCorrupted() {
        AnalyzedSpecification spec = analyzer.analyze("""
                openapi: 3.0.3
                info: {title: Aliases, version: '1'}
                x-shared-response: &ok
                  description: OK
                  content:
                    application/json:
                      schema: {type: string, example: 2026-10-21}
                paths:
                  /a:
                    get:
                      responses:
                        '200': *ok
                  /b:
                    get:
                      responses:
                        '200': *ok
                """.getBytes(StandardCharsets.UTF_8));

        for (String key : List.of("GET /a", "GET /b")) {
            Map<String, Object> response = operation(spec, key).responses().getFirst();
            assertThat(response).containsEntry("description", "OK");
            // YAML 1.1 timestamps stay strings instead of becoming dates or numbers.
            assertThat(schemaOf(response)).containsEntry("example", "2026-10-21");
        }
    }

    @Test
    void recursiveYamlAnchorIsRejected() {
        assertThat(rejection("openapi: 3.0.3\nx-loop: &loop [1, *loop]\n").code()).isEqualTo(Code.LIMIT_EXCEEDED);
    }

    @Test
    void excessiveNestingIsRejected() {
        String deep = "{\"openapi\": \"3.0.3\", \"x\": " + "[".repeat(150) + "]".repeat(150) + "}";

        assertThat(rejection(deep).code()).isEqualTo(Code.LIMIT_EXCEEDED);
    }

    @Test
    void operationCountLimitIsEnforced() {
        SpecificationAnalyzer small = analyzer(2);

        assertThatThrownBy(() -> small.analyze(fixture("pets-3.0.json")))
                .isInstanceOfSatisfying(SpecificationRejectedException.class,
                        ex -> assertThat(ex.messages()).anySatisfy(message -> {
                            assertThat(message.code()).isEqualTo(Code.LIMIT_EXCEEDED);
                            assertThat(message.message()).contains("3 operations").contains("limit is 2");
                        }));
    }

    @Test
    void rejectionListsErrorsBeforeWarnings() {
        List<ImportMessage> messages = rejectionMessages("""
                openapi: 3.0.3
                info: {title: X, version: '1'}
                paths:
                  /a:
                    get: {operationId: same, responses: {'200': {description: OK}}}
                    post: {operationId: same, responses: {'200': {description: OK}}}
                """);

        assertThat(messages.getFirst().severity()).isEqualTo(Severity.ERROR);
    }

    // ---- helpers -----------------------------------------------------------------------------------

    static SpecificationAnalyzer analyzer(int maxOperations) {
        return new SpecificationAnalyzer(new ApiForgeProperties(new ApiForgeProperties.Cors(List.of()),
                new ApiForgeProperties.Auth(true, 4, 5, Duration.ofMinutes(15), Duration.ofHours(12), 10, 5, false),
                new ApiForgeProperties.Specification(DataSize.ofMegabytes(5), maxOperations, 100, 50)));
    }

    static byte[] fixture(String name) {
        try (InputStream stream = SpecificationAnalyzerTest.class.getResourceAsStream("/specs/" + name)) {
            if (stream == null) {
                throw new IllegalArgumentException("Missing fixture " + name);
            }
            return stream.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static String withSchemaRef(String ref) {
        return withSchemaRefValue("'" + ref + "'");
    }

    private static String withSchemaRefValue(String yamlValue) {
        return """
                openapi: 3.0.3
                info: {title: X, version: '1'}
                paths:
                  /pets:
                    get:
                      responses:
                        '200':
                          description: OK
                          content:
                            application/json:
                              schema:
                                $ref: %s
                """.formatted(yamlValue);
    }

    private ImportMessage rejection(String document) {
        return rejection(document.getBytes(StandardCharsets.UTF_8));
    }

    private ImportMessage rejection(byte[] document) {
        return rejectionMessages(document).getFirst();
    }

    private List<ImportMessage> rejectionMessages(String document) {
        return rejectionMessages(document.getBytes(StandardCharsets.UTF_8));
    }

    private List<ImportMessage> rejectionMessages(byte[] document) {
        try {
            analyzer.analyze(document);
        } catch (SpecificationRejectedException ex) {
            assertThat(ex.messages()).isNotEmpty();
            assertThat(ex.messages().getFirst().isError()).isTrue();
            return ex.messages();
        }
        throw new AssertionError("Expected the document to be rejected");
    }

    private static List<String> keys(AnalyzedSpecification spec) {
        return spec.operations().stream().map(ApiOperation.Contents::operationKey).toList();
    }

    private static ApiOperation.Contents operation(AnalyzedSpecification spec, String key) {
        return spec.operations().stream().filter(op -> op.operationKey().equals(key)).findFirst().orElseThrow();
    }

    private static List<String> messageCodes(List<Map<String, Object>> messages) {
        return messages.stream().map(message -> (String) message.get("code")).toList();
    }

    private static Map<String, Object> schemaOf(Map<String, Object> response) {
        return map(map(map(response.get("content")).get("application/json")).get("schema"));
    }

    // Normalized JSON is untyped (Map<String, Object>); this test-only helper casts nested objects.
    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
