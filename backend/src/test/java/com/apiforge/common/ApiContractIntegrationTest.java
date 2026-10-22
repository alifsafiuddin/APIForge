package com.apiforge.common;

import static org.assertj.core.api.Assertions.assertThat;

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

/**
 * The HTTP contract every controller shares, checked over real HTTP against real endpoints: how bad
 * requests are answered (never a 500), the problem+json document, request ids, created resources
 * and pages.
 */
@IntegrationTest
class ApiContractIntegrationTest {

    @LocalServerPort
    int port;

    BrowserClient browser;
    String projectId;

    @BeforeEach
    void setUp() {
        browser = TestUsers.register(port).client();
        projectId = TestProjects.create(browser, "Contract " + UUID.randomUUID());
    }

    /** A problem+json answer with every documented member, its request id echoed in the header. */
    private static JsonNode problem(Response response, int status, String code) {
        assertThat(response.status()).as(response.body()).isEqualTo(status);
        assertThat(response.header("Content-Type")).hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = response.json();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("code").asString()).isEqualTo(code);
        assertThat(body.path("title").asString()).isNotBlank();
        assertThat(body.path("detail").asString()).isNotBlank();
        assertThat(body.path("instance").asString()).startsWith("/");
        assertThat(body.path("requestId").asString()).isNotBlank();
        assertThat(response.header("X-Request-Id")).contains(body.path("requestId").asString());
        assertThat(response.body()).doesNotContain("Exception").doesNotContain("at com.apiforge");
        return body;
    }

    @Test
    void malformedJsonIsABadRequest() {
        problem(browser.raw("POST", "/api/v1/projects", "application/json", "{\"name\": "), 400, "MALFORMED_REQUEST");
        problem(browser.raw("POST", "/api/v1/projects", "application/json", "[]"), 400, "MALFORMED_REQUEST");
    }

    @Test
    void wrongJsonTypesAndUnknownPropertiesAreRejected() {
        problem(browser.post("/api/v1/projects", Map.of("name", Map.of("nested", true))), 400, "MALFORMED_REQUEST");
        problem(browser.post("/api/v1/projects", Map.of("name", "Valid", "colour", "blue")), 400, "MALFORMED_REQUEST");
    }

    @Test
    void beanValidationListsEveryInvalidField() {
        JsonNode body = problem(browser.post("/api/v1/projects", Map.of("name", "", "description", "x".repeat(2001))),
                400, "VALIDATION_ERROR");
        assertThat(body.path("errors").valueStream().map(error -> error.path("field").asString()))
                .contains("name", "description");
        assertThat(body.path("errors").valueStream().map(error -> error.path("message").asString()))
                .allSatisfy(message -> assertThat(message).isNotBlank());
    }

    @Test
    void unsupportedMediaTypeAndMethodAreReportedAsSuch() {
        problem(browser.raw("POST", "/api/v1/projects", "text/plain", "name=x"), 415, "UNSUPPORTED_MEDIA_TYPE");
        Response method = browser.raw("PUT", "/api/v1/projects", "application/json", "{}");
        problem(method, 405, "METHOD_NOT_ALLOWED");
        assertThat(method.header("Allow")).isPresent();
    }

    @Test
    void malformedPathAndQueryParametersAreBadRequestsNotServerErrors() {
        // Values of the wrong type are malformed requests (API_DESIGN §1); disallowed sort properties are validation errors.
        problem(browser.get("/api/v1/projects/not-a-uuid"), 400, "MALFORMED_REQUEST");
        problem(browser.get("/api/v1/projects/" + projectId + "/runs?status=FINISHED"), 400, "MALFORMED_REQUEST");
        problem(browser.get("/api/v1/projects/" + projectId + "/runs?failing=maybe"), 400, "MALFORMED_REQUEST");
        problem(browser.get("/api/v1/projects?archived=perhaps"), 400, "MALFORMED_REQUEST");
        problem(browser.get("/api/v1/projects?sort=passwordHash,asc"), 400, "VALIDATION_ERROR");
    }

    @Test
    void unknownResourcesAndPathsAreNotFound() {
        problem(browser.get("/api/v1/projects/" + UUID.randomUUID()), 404, "NOT_FOUND");
        problem(browser.get("/api/v1/projects/" + projectId + "/runs/" + UUID.randomUUID()), 404, "NOT_FOUND");
        problem(browser.get("/api/v1/no-such-endpoint"), 404, "NOT_FOUND");
    }

    @Test
    void createdResourcesHaveALocationAndTheirRepresentation() {
        Response created = browser.post("/api/v1/projects", Map.of("name", "Located " + UUID.randomUUID()));

        assertThat(created.status()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(created.header("Location")).contains("/api/v1/projects/" + id);
        // Timestamps included: the clock ticks in microseconds like PostgreSQL, so a read returns what the write did.
        assertThat(browser.get("/api/v1/projects/" + id).json()).isEqualTo(created.json());
        for (String field : new String[] { "id", "name", "archived", "createdAt", "updatedAt", "version" }) {
            assertThat(created.json().has(field)).as(field).isTrue();
        }
    }

    @Test
    void pagesHaveOneShapeAndACappedSize() {
        for (int index = 0; index < 3; index++) {
            browser.post("/api/v1/projects", Map.of("name", "Paged " + index + " " + UUID.randomUUID()));
        }

        JsonNode page = browser.get("/api/v1/projects?size=2&page=1&sort=name,asc").json();
        assertThat(page.path("content")).hasSize(2);
        assertThat(page.path("page").path("number").asInt()).isEqualTo(1);
        assertThat(page.path("page").path("size").asInt()).isEqualTo(2);
        assertThat(page.path("page").path("totalElements").asInt()).isEqualTo(4);
        assertThat(page.path("page").path("totalPages").asInt()).isEqualTo(2);
        assertThat(browser.get("/api/v1/projects?size=100000").json().path("page").path("size").asInt()).isEqualTo(100);
    }

    @Test
    void noContentAnswersHaveNoBody() {
        Response deleted = browser.delete("/api/v1/projects/" + projectId);

        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.body()).isEmpty();
    }

    @Test
    void errorsCarryTheCallersRequestIdWhenItIsSafe() {
        Response response = browser.get("/api/v1/projects/" + UUID.randomUUID());

        assertThat(response.json().path("requestId").asString()).matches("[0-9a-f-]{36}");
    }
}
