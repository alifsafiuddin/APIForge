package com.apiforge.dashboard.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Read-only summaries for the dashboard and project overview; every number is counted from stored rows. */
public final class DashboardViews {

    private DashboardViews() {
    }

    /**
     * @param runsLast7Days stored test runs (not console requests) created in the last seven days
     * @param runsWithFailuresLast7Days of those, runs with at least one failed or errored test
     * @param runsInProgress queued or running test runs
     * @param testsPendingReview active tests in active projects that wait for review (AI suggestions)
     * @param staleTests tests in active projects whose operation changed or disappeared in a newer version
     */
    public record Totals(int activeProjects, int archivedProjects, int runsLast7Days, int runsWithFailuresLast7Days,
            int runsInProgress, int testsPendingReview, int staleTests) {
    }

    public record SpecificationRef(UUID id, int versionNumber, String title, String apiVersion, int operations) {
    }

    /** @param active tests in the active lifecycle (enabled or not) */
    public record TestCounts(int active, int enabled, int pendingReview, int stale, int suites) {
    }

    public record RunRef(UUID id, UUID projectId, String projectName, String status, String environmentName,
            int total, int passed, int failed, int error, int inconclusive, int skipped, Instant createdAt,
            Instant finishedAt) {
    }

    /** The latest coverage snapshot of the active version, as stored when it was taken. */
    public record CoverageRef(Instant takenAt, int operations, int operationsWithTests, int responses,
            int responsesExercised, int executed, int passed) {
    }

    public record ProjectSummary(UUID id, String name, String description, boolean archived, Instant updatedAt,
            SpecificationRef activeSpecification, TestCounts tests, int environments, RunRef lastRun,
            CoverageRef coverage) {
    }

    /** @param projects the most recently active projects, at most {@code DashboardService.PROJECT_LIMIT} */
    public record Dashboard(Totals totals, List<ProjectSummary> projects, List<RunRef> recentRuns) {
    }

    public record ProjectOverview(ProjectSummary project, List<RunRef> recentRuns) {
    }
}
