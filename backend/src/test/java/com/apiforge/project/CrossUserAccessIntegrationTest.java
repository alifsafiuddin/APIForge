package com.apiforge.project;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.apiforge.support.BrowserClient.Response;
import com.apiforge.support.HistoryFixtures;
import com.apiforge.support.HistoryFixtures.SeededRun;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestUsers;
import com.apiforge.support.TestUsers.SignedInUser;

/**
 * User B must not be able to see, change, delete or even detect user A's projects and
 * execution history. Every attempt returns the same 404 as a non-existent resource and
 * leaves A's data untouched.
 */
@IntegrationTest
class CrossUserAccessIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    HistoryFixtures history;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AuditEventRepository auditEvents;

    SignedInUser owner;
    SignedInUser intruder;
    String projectId;
    SeededRun run;

    @BeforeEach
    void setUp() {
        owner = TestUsers.register(port);
        intruder = TestUsers.register(port);
        projectId = ProjectIntegrationTest.createProject(owner.client(), "Owner's private project");
        run = history.completedRun(UUID.fromString(projectId), owner.id());
    }

    @Test
    void intruderReceivesNotFoundForEveryProjectScopedReadEndpoint() {
        List<String> paths = List.of(
                "/api/v1/projects/" + projectId,
                "/api/v1/projects/" + projectId + "/runs",
                "/api/v1/projects/" + projectId + "/runs/" + run.runId(),
                "/api/v1/projects/" + projectId + "/runs/" + run.runId() + "/results",
                "/api/v1/projects/" + projectId + "/runs/" + run.runId() + "/results/" + run.passedResultId());

        for (String path : paths) {
            assertThat(owner.client().get(path).status()).as("owner " + path).isEqualTo(200);
            Response response = intruder.client().get(path);
            assertThat(response.status()).as("intruder " + path).isEqualTo(404);
            assertThat(response.code()).isEqualTo("NOT_FOUND");
            assertThat(response.body()).doesNotContain("Owner's private project").doesNotContain("staging");
        }
    }

    @Test
    void foreignProjectIsIndistinguishableFromNonExistentOne() {
        Response foreign = intruder.client().get("/api/v1/projects/" + projectId);
        Response missing = intruder.client().get("/api/v1/projects/" + UUID.randomUUID());

        assertThat(foreign.status()).isEqualTo(missing.status());
        assertThat(foreign.json().path("detail")).isEqualTo(missing.json().path("detail"));
        assertThat(foreign.json().path("code")).isEqualTo(missing.json().path("code"));
    }

    @Test
    void intruderCannotModifyOrDeleteAndOwnerDataIsUnchanged() {
        Response update = intruder.client().patch("/api/v1/projects/" + projectId,
                Map.of("name", "Hijacked", "archived", true, "version", 0));
        Response delete = intruder.client().delete("/api/v1/projects/" + projectId);

        assertThat(update.status()).isEqualTo(404);
        assertThat(delete.status()).isEqualTo(404);
        Response ownerView = owner.client().get("/api/v1/projects/" + projectId);
        assertThat(ownerView.json().path("name").asString()).isEqualTo("Owner's private project");
        assertThat(ownerView.json().path("archived").asBoolean()).isFalse();
        assertThat(ownerView.json().path("version").asLong()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from test_results where test_run_id = ?", Integer.class,
                run.runId())).isEqualTo(2);
    }

    @Test
    void listingsOnlyContainOwnProjects() {
        ProjectIntegrationTest.createProject(intruder.client(), "Intruder's project");

        List<String> intruderNames = intruder.client().get("/api/v1/projects").json().path("content").valueStream()
                .map(node -> node.path("name").asString()).toList();
        List<String> ownerNames = owner.client().get("/api/v1/projects").json().path("content").valueStream()
                .map(node -> node.path("name").asString()).toList();

        assertThat(intruderNames).containsExactly("Intruder's project");
        assertThat(ownerNames).containsExactly("Owner's private project");
    }

    @Test
    void runFromAnotherProjectCannotBeReachedThroughOwnProject() {
        String intruderProject = ProjectIntegrationTest.createProject(intruder.client(), "Decoy");

        Response viaOwnProject = intruder.client().get(
                "/api/v1/projects/" + intruderProject + "/runs/" + run.runId());
        Response resultViaOwnProject = intruder.client().get(
                "/api/v1/projects/" + intruderProject + "/runs/" + run.runId() + "/results/" + run.passedResultId());

        assertThat(viaOwnProject.status()).isEqualTo(404);
        assertThat(resultViaOwnProject.status()).isEqualTo(404);
    }

    @Test
    void crossUserAttemptsAreAudited() {
        intruder.client().get("/api/v1/projects/" + projectId);

        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(intruder.id(),
                AuditEventType.CROSS_USER_ACCESS_DENIED))
                .singleElement()
                .satisfies(event -> assertThat(event.getProjectId()).hasToString(projectId));
        // Probing random ids is not reported as a cross-user attempt.
        intruder.client().get("/api/v1/projects/" + UUID.randomUUID());
        assertThat(auditEvents.findByUserIdAndEventTypeOrderByOccurredAtDesc(intruder.id(),
                AuditEventType.CROSS_USER_ACCESS_DENIED)).hasSize(1);
    }

    @Test
    void ownerSeesFullExecutionHistory() {
        Response runs = owner.client().get("/api/v1/projects/" + projectId + "/runs");
        Response results = owner.client().get("/api/v1/projects/" + projectId + "/runs/" + run.runId() + "/results");
        Response errorDetail = owner.client().get("/api/v1/projects/" + projectId + "/runs/" + run.runId()
                + "/results/" + run.errorResultId());

        assertThat(runs.json().path("page").path("totalElements").asLong()).isEqualTo(1);
        assertThat(runs.json().path("content").get(0).path("counts").path("passed").asInt()).isEqualTo(1);
        assertThat(runs.json().path("content").get(0).path("environmentName").asString()).isEqualTo("staging");
        assertThat(results.json().path("content").valueStream().map(node -> node.path("outcome").asString()))
                .containsExactly("PASSED", "ERROR");
        assertThat(errorDetail.json().path("errorKind").asString()).isEqualTo("TIMEOUT");
        assertThat(errorDetail.json().path("response").isNull()).isTrue();
    }
}
