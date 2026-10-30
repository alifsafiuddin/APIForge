package com.apiforge.dashboard.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.dashboard.service.DashboardViews.CoverageRef;
import com.apiforge.dashboard.service.DashboardViews.Dashboard;
import com.apiforge.dashboard.service.DashboardViews.ProjectOverview;
import com.apiforge.dashboard.service.DashboardViews.ProjectSummary;
import com.apiforge.dashboard.service.DashboardViews.RunRef;
import com.apiforge.dashboard.service.DashboardViews.SpecificationRef;
import com.apiforge.dashboard.service.DashboardViews.TestCounts;
import com.apiforge.dashboard.service.DashboardViews.Totals;
import com.apiforge.project.service.ProjectAccessService;

/**
 * Cross-module summaries for the signed-in user's home page and each project's overview. A read
 * model: plain SQL aggregates over the other modules' tables, so no module depends on this one and
 * the numbers are always the stored ones (no estimates, nothing cached).
 */
@Service
@Transactional(readOnly = true)
public class DashboardService {

    static final int PROJECT_LIMIT = 12;
    static final int RECENT_RUNS = 8;
    static final Duration RECENT_WINDOW = Duration.ofDays(7);

    private static final String RUN_COLUMNS = """
            r.id AS run_id, r.project_id AS run_project_id, p.name AS run_project_name, r.status AS run_status,
            r.environment_name_snapshot AS run_environment, r.total_count AS run_total, r.passed_count AS run_passed,
            r.failed_count AS run_failed, r.error_count AS run_error, r.inconclusive_count AS run_inconclusive,
            r.skipped_count AS run_skipped, r.created_at AS run_created_at, r.finished_at AS run_finished_at""";

    private static final String PROJECT_SUMMARY = """
            SELECT p.id, p.name, p.description, p.archived_at, p.updated_at,
                   sv.id AS sv_id, sv.version_number, sv.title, sv.api_version,
                   (SELECT count(*) FROM api_operations o WHERE o.specification_version_id = sv.id) AS operations,
                   t.active, t.enabled, t.pending_review, t.stale,
                   (SELECT count(*) FROM test_suites s WHERE s.project_id = p.id) AS suites,
                   (SELECT count(*) FROM environments e WHERE e.project_id = p.id) AS environments,
                   lr.*, cs.*
            FROM api_projects p
            LEFT JOIN specification_versions sv ON sv.id = p.active_specification_version_id
            CROSS JOIN LATERAL (
                SELECT count(*) FILTER (WHERE tc.lifecycle_status = 'ACTIVE') AS active,
                       count(*) FILTER (WHERE tc.lifecycle_status = 'ACTIVE' AND tc.enabled) AS enabled,
                       count(*) FILTER (WHERE tc.lifecycle_status = 'ACTIVE' AND tc.review_status = 'PENDING_REVIEW')
                           AS pending_review,
                       count(*) FILTER (WHERE tc.lifecycle_status = 'STALE') AS stale
                FROM test_cases tc WHERE tc.project_id = p.id) t
            LEFT JOIN LATERAL (
                SELECT %s
                FROM test_runs r WHERE r.project_id = p.id AND r.kind = 'TEST_RUN'
                ORDER BY r.created_at DESC LIMIT 1) lr ON true
            LEFT JOIN LATERAL (
                SELECT c.taken_at AS cov_taken_at,
                       (c.metrics ->> 'operations')::int AS cov_operations,
                       (c.metrics ->> 'operationsWithTests')::int AS cov_operations_with_tests,
                       (c.metrics ->> 'responses')::int AS cov_responses,
                       (c.metrics ->> 'responsesExercised')::int AS cov_responses_exercised,
                       (c.metrics ->> 'executed')::int AS cov_executed,
                       (c.metrics ->> 'passed')::int AS cov_passed
                FROM coverage_snapshots c WHERE c.specification_version_id = sv.id
                ORDER BY c.taken_at DESC LIMIT 1) cs ON true
            """.formatted(RUN_COLUMNS);

    private final JdbcClient jdbc;
    private final ProjectAccessService access;
    private final Clock clock;

    public DashboardService(JdbcClient jdbc, ProjectAccessService access, Clock clock) {
        this.jdbc = jdbc;
        this.access = access;
        this.clock = clock;
    }

    public Dashboard dashboard(UUID userId) {
        Timestamp since = Timestamp.from(Instant.now(clock).minus(RECENT_WINDOW));
        Totals totals = jdbc.sql("""
                SELECT (SELECT count(*) FROM api_projects p WHERE p.owner_id = :owner AND p.archived_at IS NULL) AS active,
                       (SELECT count(*) FROM api_projects p WHERE p.owner_id = :owner AND p.archived_at IS NOT NULL)
                           AS archived,
                       r.recent, r.recent_failing, r.in_progress, t.pending_review, t.stale
                FROM (SELECT count(*) FILTER (WHERE r.created_at >= :since) AS recent,
                             count(*) FILTER (WHERE r.created_at >= :since AND r.failed_count + r.error_count > 0)
                                 AS recent_failing,
                             count(*) FILTER (WHERE r.status IN ('QUEUED', 'RUNNING')) AS in_progress
                      FROM test_runs r JOIN api_projects p ON p.id = r.project_id
                      WHERE p.owner_id = :owner AND r.kind = 'TEST_RUN') r,
                     (SELECT count(*) FILTER (WHERE tc.lifecycle_status = 'ACTIVE'
                                 AND tc.review_status = 'PENDING_REVIEW') AS pending_review,
                             count(*) FILTER (WHERE tc.lifecycle_status = 'STALE') AS stale
                      FROM test_cases tc JOIN api_projects p ON p.id = tc.project_id
                      WHERE p.owner_id = :owner AND p.archived_at IS NULL) t
                """).param("owner", userId).param("since", since)
                .query((rs, row) -> new Totals(rs.getInt("active"), rs.getInt("archived"), rs.getInt("recent"),
                        rs.getInt("recent_failing"), rs.getInt("in_progress"), rs.getInt("pending_review"),
                        rs.getInt("stale")))
                .single();
        List<ProjectSummary> projects = jdbc.sql(PROJECT_SUMMARY + """
                WHERE p.owner_id = :owner AND p.archived_at IS NULL
                ORDER BY greatest(p.updated_at, coalesce(lr.run_created_at, p.updated_at)) DESC, p.name
                LIMIT :limit
                """).param("owner", userId).param("limit", PROJECT_LIMIT)
                .query((rs, row) -> summary(rs)).list();
        List<RunRef> recentRuns = jdbc.sql("SELECT " + RUN_COLUMNS + """

                FROM test_runs r JOIN api_projects p ON p.id = r.project_id
                WHERE p.owner_id = :owner AND r.kind = 'TEST_RUN'
                ORDER BY r.created_at DESC LIMIT :limit
                """).param("owner", userId).param("limit", RECENT_RUNS)
                .query((rs, row) -> run(rs)).list();
        return new Dashboard(totals, projects, recentRuns);
    }

    public ProjectOverview overview(UUID userId, UUID projectId) {
        access.requireOwned(projectId, userId);
        ProjectSummary project = jdbc.sql(PROJECT_SUMMARY + "WHERE p.id = :project")
                .param("project", projectId).query((rs, row) -> summary(rs)).single();
        List<RunRef> recentRuns = jdbc.sql("SELECT " + RUN_COLUMNS + """

                FROM test_runs r JOIN api_projects p ON p.id = r.project_id
                WHERE r.project_id = :project AND r.kind = 'TEST_RUN'
                ORDER BY r.created_at DESC LIMIT :limit
                """).param("project", projectId).param("limit", RECENT_RUNS)
                .query((rs, row) -> run(rs)).list();
        return new ProjectOverview(project, recentRuns);
    }

    private static ProjectSummary summary(ResultSet rs) throws SQLException {
        SpecificationRef specification = rs.getObject("sv_id") == null ? null
                : new SpecificationRef(rs.getObject("sv_id", UUID.class), rs.getInt("version_number"),
                        rs.getString("title"), rs.getString("api_version"), rs.getInt("operations"));
        TestCounts tests = new TestCounts(rs.getInt("active"), rs.getInt("enabled"), rs.getInt("pending_review"),
                rs.getInt("stale"), rs.getInt("suites"));
        CoverageRef coverage = rs.getTimestamp("cov_taken_at") == null ? null
                : new CoverageRef(instant(rs, "cov_taken_at"), rs.getInt("cov_operations"),
                        rs.getInt("cov_operations_with_tests"), rs.getInt("cov_responses"),
                        rs.getInt("cov_responses_exercised"), rs.getInt("cov_executed"), rs.getInt("cov_passed"));
        return new ProjectSummary(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getTimestamp("archived_at") != null, instant(rs, "updated_at"), specification, tests,
                rs.getInt("environments"), rs.getObject("run_id") == null ? null : run(rs), coverage);
    }

    private static RunRef run(ResultSet rs) throws SQLException {
        return new RunRef(rs.getObject("run_id", UUID.class), rs.getObject("run_project_id", UUID.class),
                rs.getString("run_project_name"), rs.getString("run_status"), rs.getString("run_environment"),
                rs.getInt("run_total"), rs.getInt("run_passed"), rs.getInt("run_failed"), rs.getInt("run_error"),
                rs.getInt("run_inconclusive"), rs.getInt("run_skipped"), instant(rs, "run_created_at"),
                instant(rs, "run_finished_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
