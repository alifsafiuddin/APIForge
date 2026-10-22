package com.apiforge.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.apiforge.auth.domain.User;
import com.apiforge.auth.repository.UserRepository;
import com.apiforge.environment.domain.Environment;
import com.apiforge.environment.domain.EnvironmentVariable;
import com.apiforge.environment.repository.EnvironmentRepository;
import com.apiforge.environment.repository.EnvironmentVariableRepository;
import com.apiforge.project.domain.Project;
import com.apiforge.project.repository.ProjectRepository;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.repository.SpecificationVersionRepository;
import com.apiforge.support.HistoryFixtures;
import com.apiforge.support.HistoryFixtures.SeededRun;
import com.apiforge.support.IntegrationTest;
import com.apiforge.support.TestUsers;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.domain.TestSuite;
import com.apiforge.testcase.repository.TestCaseRepository;
import com.apiforge.testcase.repository.TestSuiteRepository;

/**
 * Verifies the migrated schema against real PostgreSQL: every migration applies, entities
 * round-trip (including jsonb), and the integrity rules enforced by the database hold even
 * when application code is bypassed.
 */
@IntegrationTest
class SchemaIntegrationTest {

    private static final String HASH = "a".repeat(64);

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    @Autowired
    ProjectRepository projects;

    @Autowired
    SpecificationVersionRepository specifications;

    @Autowired
    EnvironmentRepository environments;

    @Autowired
    EnvironmentVariableRepository variables;

    @Autowired
    TestSuiteRepository suites;

    @Autowired
    TestCaseRepository testCases;

    @Autowired
    HistoryFixtures history;

    User user;
    Project project;

    @BeforeEach
    void setUp() {
        user = users.saveAndFlush(new User(TestUsers.uniqueEmail(), "{bcrypt}$2a$04$" + "x".repeat(53), "Schema"));
        project = projects.saveAndFlush(new Project(user.getId(), "Schema " + UUID.randomUUID(), null));
    }

    @Test
    void allMigrationsAppliedInOrder() {
        MigrationInfo[] applied = flyway.info().applied();

        assertThat(applied).extracting(info -> info.getVersion().getVersion())
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
        assertThat(applied).allSatisfy(info -> assertThat(info.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void expectedTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tables).contains("users", "audit_events", "api_projects", "specification_versions", "environments",
                "environment_variables", "test_suites", "test_cases", "test_runs", "test_results", "api_operations");
    }

    @Test
    void deterministicTestsNeedAScenarioKey() {
        TestSuite suite = suites.saveAndFlush(new TestSuite(project.getId(), "Generated", null, TestSuite.Kind.GENERATED));

        assertThatThrownBy(() -> testCases.saveAndFlush(new TestCase(project.getId(), suite.getId(), "GET /pets",
                "No identity", TestCase.Category.HAPPY_PATH, TestCase.Origin.DETERMINISTIC, TestCase.RiskLevel.READ_ONLY,
                Map.of(), Map.of(), HASH)))
                .hasMessageContaining("ck_test_cases_scenario_key");
    }

    @Test
    void entitiesRoundTripIncludingJsonColumnsAndAuditTimestamps() {
        SpecificationVersion spec = specifications.saveAndFlush(specification(project.getId(), 1));
        TestSuite suite = suites.saveAndFlush(new TestSuite(project.getId(), "Generated", null, TestSuite.Kind.GENERATED));
        TestCase testCase = testCases.saveAndFlush(new TestCase(project.getId(), suite.getId(), "GET /pets/{id}",
                "Get pet", TestCase.Category.HAPPY_PATH, TestCase.Origin.MANUAL, TestCase.RiskLevel.READ_ONLY,
                Map.of("pathParams", Map.of("id", 7)), Map.of("expectedStatuses", List.of("200")), HASH));

        SpecificationVersion reloadedSpec = specifications.findById(spec.getId()).orElseThrow();
        TestCase reloadedCase = testCases.findById(testCase.getId()).orElseThrow();

        assertThat(reloadedSpec.getServers()).containsExactly(Map.of("url", "https://api.example.test"));
        assertThat(reloadedCase.getRequestTemplate()).isEqualTo(Map.of("pathParams", Map.of("id", 7)));
        assertThat(reloadedCase.getReviewStatus()).isEqualTo(TestCase.ReviewStatus.APPROVED);
        assertThat(reloadedCase.getCreatedAt()).isNotNull();
        assertThat(reloadedCase.getUpdatedAt()).isNotNull();
        assertThat(project.getId().version()).as("UUIDv7 ids").isEqualTo(7);
    }

    @Test
    void plaintextPasswordCannotBeStored() {
        assertThatThrownBy(() -> jdbc.update("""
                insert into users (id, email, password_hash, display_name, created_at, updated_at)
                values (?, ?, 'hunter2-plaintext-password', 'X', now(), now())""",
                UUID.randomUUID(), TestUsers.uniqueEmail()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("ck_users_password_hash_encoded");
    }

    @Test
    void secretVariablesCanOnlyBeStoredEncrypted() {
        Environment environment = environments.saveAndFlush(
                new Environment(project.getId(), "local", "http://localhost:8090"));

        variables.saveAndFlush(EnvironmentVariable.plain(environment.getId(), "petId", "7"));
        variables.saveAndFlush(EnvironmentVariable.secret(environment.getId(), "token", new byte[] { 1, 2, 3 }, "k1"));

        assertThatThrownBy(() -> jdbc.update("""
                insert into environment_variables (id, environment_id, name, secret, plain_value, created_at, updated_at)
                values (?, ?, 'apiKey', true, 'leaked-secret', now(), now())""", UUID.randomUUID(), environment.getId()))
                .hasMessageContaining("ck_environment_variables_storage");
    }

    @Test
    void aiSuggestedTestCannotBeApprovedWithoutReview() {
        TestSuite suite = suites.saveAndFlush(new TestSuite(project.getId(), "AI", null, TestSuite.Kind.AI_SUGGESTIONS));
        TestCase suggestion = testCases.saveAndFlush(new TestCase(project.getId(), suite.getId(), "POST /pets",
                "AI idea", TestCase.Category.CUSTOM, TestCase.Origin.AI_SUGGESTED, TestCase.RiskLevel.MUTATING,
                Map.of(), Map.of(), "b".repeat(64)));
        assertThat(suggestion.getReviewStatus()).isEqualTo(TestCase.ReviewStatus.PENDING_REVIEW);

        assertThatThrownBy(() -> jdbc.update("update test_cases set review_status = 'APPROVED' where id = ?",
                suggestion.getId()))
                .hasMessageContaining("ck_test_cases_ai_review");
    }

    @Test
    void childRowsCannotPointAtAnotherProjectsData() {
        Project otherProject = projects.saveAndFlush(new Project(user.getId(), "Other " + UUID.randomUUID(), null));
        TestSuite otherSuite = suites.saveAndFlush(
                new TestSuite(otherProject.getId(), "Theirs", null, TestSuite.Kind.CUSTOM));
        SpecificationVersion otherSpec = specifications.saveAndFlush(specification(otherProject.getId(), 1));

        assertThatThrownBy(() -> testCases.saveAndFlush(new TestCase(project.getId(), otherSuite.getId(), "GET /x",
                "Cross-project", TestCase.Category.CUSTOM, TestCase.Origin.MANUAL, TestCase.RiskLevel.READ_ONLY,
                Map.of(), Map.of(), "c".repeat(64))))
                .hasMessageContaining("fk_test_cases_suite");
        assertThatThrownBy(() -> jdbc.update(
                "update api_projects set active_specification_version_id = ? where id = ?",
                otherSpec.getId(), project.getId()))
                .hasMessageContaining("fk_api_projects_active_specification");
    }

    @Test
    void historyAndSpecificationsAreImmutable() {
        SeededRun run = history.completedRun(project.getId(), user.getId());
        SpecificationVersion spec = specifications.saveAndFlush(specification(project.getId(), 1));

        assertThatThrownBy(() -> jdbc.update("update test_results set outcome = 'FAILED' where id = ?",
                run.passedResultId()))
                .hasMessageContaining("test_results rows are immutable");
        assertThatThrownBy(() -> jdbc.update("update specification_versions set raw_content = 'tampered' where id = ?",
                spec.getId()))
                .hasMessageContaining("specification_versions rows are immutable");
        UUID auditId = UUID.randomUUID();
        jdbc.update("insert into audit_events (id, event_type, user_id, occurred_at) values (?, 'LOGOUT', ?, now())",
                auditId, user.getId());
        assertThatThrownBy(() -> jdbc.update("update audit_events set event_type = 'LOGIN_SUCCEEDED' where id = ?",
                auditId))
                .hasMessageContaining("audit_events rows are append-only");
    }

    @Test
    void passedResultRequiresAResponse() {
        SeededRun run = history.completedRun(project.getId(), user.getId());

        assertThatThrownBy(() -> jdbc.update("""
                insert into test_results (id, test_run_id, operation_key, test_case_snapshot, request_snapshot,
                                          outcome, executed_at)
                values (?, ?, 'GET /x', '{}', '{}', 'PASSED', now())""", UUID.randomUUID(), run.runId()))
                .hasMessageContaining("ck_test_results_passed_has_response");
    }

    @Test
    void timeToHeadersCannotExceedTheDuration() {
        SeededRun run = history.completedRun(project.getId(), user.getId());

        assertThatThrownBy(() -> jdbc.update("""
                insert into test_results (id, test_run_id, operation_key, test_case_snapshot, request_snapshot,
                                          outcome, executed_at, response_status, duration_ms, time_to_headers_ms)
                values (?, ?, 'GET /x', '{}', '{}', 'FAILED', now(), 500, 10, 11)""", UUID.randomUUID(), run.runId()))
                .hasMessageContaining("ck_test_results_time_to_headers");
    }

    @Test
    void deletingTestCaseKeepsItsHistory() {
        TestSuite suite = suites.saveAndFlush(new TestSuite(project.getId(), "S", null, TestSuite.Kind.CUSTOM));
        TestCase testCase = testCases.saveAndFlush(new TestCase(project.getId(), suite.getId(), "GET /pets", "T",
                TestCase.Category.CUSTOM, TestCase.Origin.MANUAL, TestCase.RiskLevel.READ_ONLY, Map.of(), Map.of(),
                "d".repeat(64)));
        SeededRun run = history.completedRun(project.getId(), user.getId());
        jdbc.update("""
                insert into test_results (id, test_run_id, test_case_id, operation_key, test_case_snapshot,
                                          request_snapshot, outcome, executed_at)
                values (?, ?, ?, 'GET /pets', '{"name":"T"}', '{}', 'SKIPPED', now())""",
                UUID.randomUUID(), run.runId(), testCase.getId());

        testCases.deleteById(testCase.getId());
        testCases.flush();

        assertThat(jdbc.queryForObject(
                "select count(*) from test_results where test_run_id = ? and test_case_id is null and outcome = 'SKIPPED'",
                Integer.class, run.runId())).isEqualTo(1);
    }

    @Test
    void deletingUserCascadesOwnedDataButKeepsAuditTrail() {
        SeededRun run = history.completedRun(project.getId(), user.getId());
        specifications.saveAndFlush(specification(project.getId(), 1));
        environments.saveAndFlush(new Environment(project.getId(), "env", "http://localhost:8090"));
        jdbc.update("""
                insert into audit_events (id, event_type, user_id, details, occurred_at)
                values (?, 'LOGIN_SUCCEEDED', ?, '{}', now())""", UUID.randomUUID(), user.getId());

        users.deleteById(user.getId());
        users.flush();

        assertThat(jdbc.queryForObject("select count(*) from api_projects where owner_id = ?", Integer.class,
                user.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from test_results where test_run_id = ?", Integer.class,
                run.runId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from specification_versions where project_id = ?",
                Integer.class, project.getId())).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from audit_events where event_type = 'LOGIN_SUCCEEDED' and user_id is null "
                        + "and occurred_at > now() - interval '1 minute'", Integer.class)).isPositive();
    }

    private SpecificationVersion specification(UUID projectId, int number) {
        return new SpecificationVersion(projectId, number, new SpecificationVersion.Contents("3.0.3", "Petstore",
                "1.0.0", null, SpecificationVersion.SourceFormat.YAML, "petstore.yaml", "openapi: 3.0.3", HASH, 14,
                List.of(Map.of("url", "https://api.example.test")), Map.of(), List.of(), Map.of(), 0, List.of()),
                user.getId(), Instant.now());
    }
}
