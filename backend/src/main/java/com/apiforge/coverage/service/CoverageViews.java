package com.apiforge.coverage.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.apiforge.execution.domain.TestResult;

/** API views of specification coverage. */
public final class CoverageViews {

    private CoverageViews() {
    }

    public enum Group {
        /** What the tests that exist cover (does not depend on runs). */
        TEST_DESIGN,
        /** What the latest executions showed. */
        EXECUTION
    }

    /**
     * One metric with its definition. {@code total} is {@code null} for plain counts; otherwise the
     * metric is {@code value} out of {@code total}.
     */
    public record Metric(String id, Group group, String label, int value, Integer total, String definition) {
    }

    /**
     * Distinct enabled tests by the outcome of their latest attempted execution. {@code skipped}
     * tests were selected for runs but never sent; {@code notRun} have no result on this version.
     */
    public record OutcomeCounts(int tests, int executed, int passed, int failed, int error, int inconclusive,
            int skipped, int notRun) {
    }

    public record OperationRow(String operationKey, String method, String path, List<String> tags, boolean deprecated,
            int tests, int duplicates, int inactiveTests, int responses, int responsesTargeted, int responsesExercised,
            int constraints, int constraintsCovered, int branches, int branchesExercised,
            List<ScenarioKind> missingNegatives, List<String> undocumentedStatuses, OutcomeCounts outcomes) {
    }

    public record ResponseGap(String operationKey, String status, boolean targeted) {
    }

    public record NegativeGap(ScenarioKind kind, String label, List<String> operations) {
    }

    public record Gaps(List<String> operationsWithoutTests, List<ResponseGap> responsesNotExercised,
            List<NegativeGap> missingNegatives) {
    }

    public record VersionRef(UUID id, int number, String title, String apiVersion, Instant importedAt) {
    }

    public record CoverageReport(VersionRef version, UUID environmentId, List<Metric> metrics, OutcomeCounts outcomes,
            List<OperationRow> operations, Gaps gaps) {
    }

    // --- Drill-down of one operation ---

    /** @param duplicateOf the test this one duplicates (same request and expectations), counted once */
    public record TestView(UUID id, String name, String scenarioKey, ScenarioKind kind, UUID duplicateOf, TestResult.Outcome latestOutcome, Integer latestStatus, Instant lastExecutedAt) {
    }

    public record ScenarioView(String scenarioKey, String name, ScenarioKind kind, boolean covered, List<UUID> testIds) {
    }

    public record SkippedView(String scenario, String reason) {
    }

    /** @param receivedStatuses statuses received in runs that matched this documented response */
    public record ResponseView(String status, boolean targeted, boolean exercised, List<Integer> receivedStatuses) {
    }

    public record BranchView(String id, String path, String label, boolean exercised) {
    }

    public enum NegativeState { PRESENT, MISSING, NOT_APPLICABLE }

    public record NegativeView(ScenarioKind kind, String label, NegativeState state) {
    }

    public record OperationDetail(VersionRef version, OperationRow summary, List<TestView> tests,
            List<ScenarioView> scenarios, List<SkippedView> skipped, List<ResponseView> responses,
            List<BranchView> branches, List<NegativeView> negatives) {
    }

    // --- Changes between specification versions ---

    public enum ChangeKind { ADDED, REMOVED, CHANGED }

    /**
     * @param aspects               what changed: parameters, request body, responses, security, referenced schemas
     * @param tests                 distinct enabled tests now (0 for removed operations)
     * @param testsFromEarlierVersion enabled tests generated from an earlier version (candidates for regeneration)
     * @param staleTests            tests kept as STALE because the operation no longer exists
     */
    public record OperationChange(String operationKey, ChangeKind kind, List<String> aspects, int tests,
            int testsFromEarlierVersion, int staleTests) {
    }

    /** Summary numbers at one point in time (see {@link CoverageCalculator#summary}). */
    public record SnapshotView(UUID id, UUID specificationVersionId, int versionNumber, String trigger, UUID runId,
            Instant takenAt, Map<String, Integer> metrics) {
    }

    /**
     * @param baseline the latest snapshot of {@code from}, or {@code null} when none was recorded
     * @param current  live summary numbers of the active version
     */
    public record VersionChanges(VersionRef from, VersionRef to, List<OperationChange> operations, int unchanged,
            SnapshotView baseline, Map<String, Integer> current, List<SnapshotView> history) {
    }
}
