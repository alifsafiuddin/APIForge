package com.apiforge.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.support.BrowserClient;
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestUsers;
import com.apiforge.support.TestUsers.SignedInUser;

import tools.jackson.databind.JsonNode;

@IntegrationTest
class ProjectIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void createReadUpdateDeleteRoundTripPersists() {
        BrowserClient browser = TestUsers.register(port).client();

        Response created = browser.post("/api/v1/projects", Map.of("name", "  Petstore  ", "description", "Demo API"));
        assertThat(created.status()).isEqualTo(201);
        String id = created.json().path("id").asString();
        assertThat(created.header("Location")).contains("/api/v1/projects/" + id);
        assertThat(created.json().path("name").asString()).isEqualTo("Petstore");
        assertThat(created.json().path("archived").asBoolean()).isFalse();
        assertThat(created.json().path("version").asLong()).isZero();

        Response fetched = browser.get("/api/v1/projects/" + id);
        assertThat(fetched.status()).isEqualTo(200);
        assertThat(fetched.json().path("description").asString()).isEqualTo("Demo API");

        Response updated = browser.patch("/api/v1/projects/" + id,
                Map.of("name", "Petstore v2", "description", "", "version", 0));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().path("name").asString()).isEqualTo("Petstore v2");
        assertThat(updated.json().path("description").isNull()).isTrue();
        assertThat(updated.json().path("version").asLong()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select name from api_projects where id = ?::uuid", String.class, id))
                .isEqualTo("Petstore v2");

        assertThat(browser.delete("/api/v1/projects/" + id).status()).isEqualTo(204);
        assertThat(browser.get("/api/v1/projects/" + id).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("select count(*) from api_projects where id = ?::uuid", Integer.class, id))
                .isZero();
    }

    @Test
    void staleVersionIsRejected() {
        BrowserClient browser = TestUsers.register(port).client();
        String id = createProject(browser, "Versioned");
        assertThat(browser.patch("/api/v1/projects/" + id, Map.of("name", "First edit", "version", 0)).status())
                .isEqualTo(200);

        Response stale = browser.patch("/api/v1/projects/" + id, Map.of("name", "Second edit", "version", 0));

        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.code()).isEqualTo("STALE_VERSION");
        assertThat(browser.get("/api/v1/projects/" + id).json().path("name").asString()).isEqualTo("First edit");
    }

    @Test
    void namesAreUniquePerOwnerAmongActiveProjects() {
        BrowserClient browser = TestUsers.register(port).client();
        String id = createProject(browser, "Inventory");

        Response duplicate = browser.post("/api/v1/projects", Map.of("name", "inventory"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.code()).isEqualTo("DUPLICATE_NAME");

        // Archiving frees the name; restoring the archived project then conflicts.
        assertThat(browser.patch("/api/v1/projects/" + id, Map.of("archived", true, "version", 0)).status())
                .isEqualTo(200);
        assertThat(browser.post("/api/v1/projects", Map.of("name", "Inventory")).status()).isEqualTo(201);
        Response restore = browser.patch("/api/v1/projects/" + id, Map.of("archived", false, "version", 1));
        assertThat(restore.status()).isEqualTo(409);
        assertThat(restore.code()).isEqualTo("DUPLICATE_NAME");

        // Another user may use the same name.
        assertThat(TestUsers.register(port).client().post("/api/v1/projects", Map.of("name", "Inventory")).status())
                .isEqualTo(201);
    }

    @Test
    void validationFailuresAreReportedPerField() {
        BrowserClient browser = TestUsers.register(port).client();

        Response blank = browser.post("/api/v1/projects", Map.of("name", "   "));
        Response tooLong = browser.post("/api/v1/projects",
                Map.of("name", "n".repeat(101), "description", "d".repeat(2001)));
        Response controlChars = browser.post("/api/v1/projects", Map.of("name", "evil\r\nname"));
        Response unknownField = browser.post("/api/v1/projects", Map.of("name", "ok", "ownerId", UUID.randomUUID()));
        Response missingVersion = browser.patch("/api/v1/projects/" + createProject(browser, "Valid"),
                Map.of("name", "No version"));

        assertThat(blank.status()).isEqualTo(400);
        assertThat(fields(tooLong)).containsExactlyInAnyOrder("description", "name");
        assertThat(fields(controlChars)).containsExactly("name");
        assertThat(unknownField.status()).isEqualTo(400);
        assertThat(unknownField.code()).isEqualTo("MALFORMED_REQUEST");
        assertThat(fields(missingVersion)).containsExactly("version");
    }

    @Test
    void listIsPagedSortedAndFiltered() {
        BrowserClient browser = TestUsers.register(port).client();
        for (int i = 1; i <= 25; i++) {
            createProject(browser, "Project %02d".formatted(i));
        }
        createProject(browser, "100%_literal");

        Response firstPage = browser.get("/api/v1/projects?page=0&size=10&sort=name,asc");
        assertThat(firstPage.status()).isEqualTo(200);
        JsonNode page = firstPage.json().path("page");
        assertThat(page.path("totalElements").asLong()).isEqualTo(26);
        assertThat(page.path("totalPages").asInt()).isEqualTo(3);
        assertThat(page.path("size").asInt()).isEqualTo(10);
        assertThat(names(firstPage)).hasSize(10).startsWith("100%_literal", "Project 01", "Project 02");

        Response lastPage = browser.get("/api/v1/projects?page=2&size=10&sort=name,asc");
        assertThat(names(lastPage)).containsExactly("Project 20", "Project 21", "Project 22", "Project 23",
                "Project 24", "Project 25");

        // LIKE wildcards in the search text are matched literally.
        assertThat(names(browser.get("/api/v1/projects?q=%25_"))).containsExactly("100%_literal");
        assertThat(names(browser.get("/api/v1/projects?q=project%201&sort=name,asc")))
                .containsExactly("Project 10", "Project 11", "Project 12", "Project 13", "Project 14", "Project 15",
                        "Project 16", "Project 17", "Project 18", "Project 19");

        assertThat(browser.get("/api/v1/projects?size=1000").json().path("page").path("size").asInt()).isEqualTo(100);
        Response badSort = browser.get("/api/v1/projects?sort=ownerId,asc");
        assertThat(badSort.status()).isEqualTo(400);
        assertThat(badSort.code()).isEqualTo("VALIDATION_ERROR");
    }

    @Test
    void archivedProjectsAreListedSeparately() {
        BrowserClient browser = TestUsers.register(port).client();
        String archivedId = createProject(browser, "Old");
        createProject(browser, "Current");
        browser.patch("/api/v1/projects/" + archivedId, Map.of("archived", true, "version", 0));

        assertThat(names(browser.get("/api/v1/projects"))).containsExactly("Current");
        assertThat(names(browser.get("/api/v1/projects?archived=true"))).containsExactly("Old");
    }

    @Test
    void unknownProjectIdGives404AndMalformedIdGives400() {
        BrowserClient browser = TestUsers.register(port).client();

        Response unknown = browser.get("/api/v1/projects/" + UUID.randomUUID());
        Response malformed = browser.get("/api/v1/projects/not-a-uuid");

        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.code()).isEqualTo("NOT_FOUND");
        assertThat(malformed.status()).isEqualTo(400);
    }

    @Test
    void deletingAProjectCascadesToItsChildren() {
        SignedInUser user = TestUsers.register(port);
        String id = createProject(user.client(), "Cascade");
        UUID projectId = UUID.fromString(id);
        jdbc.update("""
                insert into environments (id, project_id, name, base_url, created_at, updated_at)
                values (?, ?, 'local', 'http://localhost:8090', now(), now())""", UUID.randomUUID(), projectId);
        jdbc.update("""
                insert into test_runs (id, project_id, status, created_at) values (?, ?, 'COMPLETED', now())""",
                UUID.randomUUID(), projectId);

        assertThat(user.client().delete("/api/v1/projects/" + id).status()).isEqualTo(204);

        assertThat(jdbc.queryForObject("select count(*) from environments where project_id = ?", Integer.class,
                projectId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from test_runs where project_id = ?", Integer.class,
                projectId)).isZero();
    }

    static String createProject(BrowserClient browser, String name) {
        Response response = browser.post("/api/v1/projects", Map.of("name", name));
        assertThat(response.status()).as(response.body()).isEqualTo(201);
        return response.json().path("id").asString();
    }

    private static List<String> names(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(200);
        return response.json().path("content").valueStream().map(node -> node.path("name").asString()).toList();
    }

    private static List<String> fields(Response response) {
        assertThat(response.status()).as(response.body()).isEqualTo(400);
        return response.json().path("errors").valueStream().map(node -> node.path("field").asString()).toList();
    }
}
