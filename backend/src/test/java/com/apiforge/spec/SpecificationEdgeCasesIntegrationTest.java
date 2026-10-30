package com.apiforge.spec;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestProjects;
import com.apiforge.support.TestUsers;

import tools.jackson.databind.JsonNode;

/** Import edge cases: parser findings translated into stable codes, unsupported documents, odd but valid input. */
@IntegrationTest
class SpecificationEdgeCasesIntegrationTest {

    @LocalServerPort
    int port;

    BrowserClient browser;
    String specs;

    @BeforeEach
    void setUp() {
        browser = TestUsers.register(port).client();
        specs = "/api/v1/projects/" + TestProjects.create(browser, "Edge " + UUID.randomUUID()) + "/specs";
    }

    private Response importText(String content) {
        return browser.post(specs, Map.of("content", content));
    }

    private static List<String> codes(Response response) {
        return response.json().path("messages").valueStream().map(message -> message.path("code").asString()).toList();
    }

    private static JsonNode message(Response response, String code) {
        return response.json().path("messages").valueStream()
                .filter(message -> message.path("code").asString().equals(code)).findFirst()
                .orElseThrow(() -> new AssertionError("no " + code + " in " + response.body()));
    }

    @Test
    void undeclaredPathParameterIsAnErrorNamingTheParameter() {
        Response rejected = importText("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /pets/{petId}:
                    get:
                      responses: {'200': {description: ok}}
                """);

        assertThat(rejected.status()).as(rejected.body()).isEqualTo(422);
        assertThat(message(rejected, "UNDECLARED_PATH_PARAMETER").path("message").asString()).contains("'petId'");
    }

    @Test
    void wronglyTypedFieldIsAnErrorWithItsLocation() {
        Response rejected = importText("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /pets:
                    get:
                      parameters: oops
                      responses: {'200': {description: ok}}
                """);

        assertThat(rejected.status()).as(rejected.body()).isEqualTo(422);
        assertThat(codes(rejected)).contains("INVALID_FIELD");
    }

    @Test
    void unknownFieldsAreWarningsAndTheDocumentStillImports() {
        Response imported = importText("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                colour: blue
                paths:
                  /pets:
                    get:
                      responses: {'200': {description: ok}}
                """);

        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
        JsonNode warning = message(imported, "UNEXPECTED_FIELD");
        assertThat(warning.path("severity").asString()).isEqualTo("WARNING");
        assertThat(warning.path("message").asString()).contains("'colour'");
    }

    @Test
    void swagger2AndUnknownOpenApiVersionsAreRejected() {
        Response swagger = importText("""
                swagger: '2.0'
                info: {title: T, version: '1'}
                paths: {}
                """);
        Response future = importText("""
                openapi: 4.0.0
                info: {title: T, version: '1'}
                paths: {}
                """);

        assertThat(swagger.status()).as(swagger.body()).isEqualTo(422);
        assertThat(future.status()).as(future.body()).isEqualTo(422);
    }

    @Test
    void nonObjectDocumentsAreRejectedWithoutAServerError() {
        for (String content : new String[] { "", "   ", "[1, 2, 3]", "just text", "42", "null" }) {
            Response rejected = importText(content);
            assertThat(rejected.status()).as("%s → %s", content, rejected.body()).isIn(400, 422);
            assertThat(rejected.json().path("code").asString()).as(content).isNotBlank();
        }
    }

    @Test
    void documentWithoutPathsImportsWithNoOperations() {
        Response imported = importText("""
                openapi: 3.1.0
                info: {title: Webhooks only, version: '1'}
                webhooks: {}
                """);

        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
        assertThat(imported.json().path("version").path("operationCount").isInt()).isTrue();
        assertThat(imported.json().path("version").path("operationCount").asInt()).isZero();
    }

    @Test
    void unicodePathsAndParameterNamesSurviveImport() {
        Response imported = importText("""
                openapi: 3.0.3
                info: {title: Ünïcode, version: '1'}
                paths:
                  /café/{größe}:
                    get:
                      parameters:
                        - {name: größe, in: path, required: true, schema: {type: string}}
                      responses: {'200': {description: ok}}
                """);

        assertThat(imported.status()).as(imported.body()).isEqualTo(201);
        String versionId = imported.json().path("version").path("id").asString();
        JsonNode operations = browser.get(specs + "/" + versionId + "/operations").json();
        assertThat(operations.toString()).contains("/café/{größe}");
    }

    @Test
    void duplicateOperationIdsAreReported() {
        Response result = importText("""
                openapi: 3.0.3
                info: {title: T, version: '1'}
                paths:
                  /a:
                    get: {operationId: same, responses: {'200': {description: ok}}}
                  /b:
                    get: {operationId: same, responses: {'200': {description: ok}}}
                """);

        assertThat(result.body()).contains("same");
        assertThat(result.status()).as(result.body()).isIn(201, 422);
    }
}
