package com.apiforge.execution.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;

/** API representations of execution history. */
public final class ExecutionHistoryViews {

    private ExecutionHistoryViews() {
    }

    public record OutcomeCounts(int total, int passed, int failed, int inconclusive, int error, int skipped) {
    }

    public record RunView(UUID id, UUID projectId, TestRun.Kind kind, TestRun.Status status, String statusReason,
            UUID environmentId, String environmentName, String baseUrl, UUID suiteId, UUID specificationVersionId,
            UUID triggeredBy, OutcomeCounts counts, Instant createdAt, Instant startedAt, Instant finishedAt) {

        public static RunView from(TestRun run) {
            return new RunView(run.getId(), run.getProjectId(), run.getKind(), run.getStatus(), run.getStatusReason(),
                    run.getEnvironmentId(),
                    run.getEnvironmentNameSnapshot(), run.getBaseUrlSnapshot(), run.getSuiteId(),
                    run.getSpecificationVersionId(), run.getTriggeredBy(),
                    new OutcomeCounts(run.getTotalCount(), run.getPassedCount(), run.getFailedCount(),
                            run.getInconclusiveCount(), run.getErrorCount(), run.getSkippedCount()),
                    run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
        }
    }

    public record ResultSummary(UUID id, UUID testCaseId, String testName, String operationKey,
            TestResult.Outcome outcome, Integer responseStatus, Integer durationMs, TestResult.ErrorKind errorKind,
            String errorMessage, int failedChecks, int skippedChecks, String firstFailure, Instant executedAt) {

        /** Skipped checks are counted separately so a passed test never hides validation that did not run. */
        static ResultSummary from(TestResult result) {
            List<Map<String, Object>> failed = result.getChecks().stream()
                    .filter(check -> "FAIL".equals(check.get("result"))).toList();
            int skipped = (int) result.getChecks().stream()
                    .filter(check -> "SKIPPED".equals(check.get("result")) || "NOT_EVALUATED".equals(check.get("result")))
                    .count();
            String firstFailure = failed.isEmpty() ? null : String.valueOf(failed.getFirst().get("message"));
            return new ResultSummary(result.getId(), result.getTestCaseId(), snapshotName(result),
                    result.getOperationKey(), result.getOutcome(), result.getResponseStatus(),
                    result.getDurationMs(), result.getErrorKind(), result.getErrorMessage(), failed.size(), skipped,
                    firstFailure, result.getExecutedAt());
        }
    }

    /** Full detail. Request and response data were redacted before they were stored. */
    public record ResultDetail(UUID id, UUID testRunId, UUID testCaseId, String testName, String operationKey,
            TestResult.Outcome outcome, Map<String, Object> request, ResponseView response,
            List<Map<String, Object>> checks, TestResult.ErrorKind errorKind, String errorMessage, Integer durationMs,
            Integer timeToHeadersMs, String correlationId, Map<String, Object> testCase, Instant executedAt) {

        public static ResultDetail from(TestResult result) {
            ResponseView response = result.getResponseStatus() == null ? null
                    : new ResponseView(result.getResponseStatus(), result.getResponseHeaders(),
                            result.getResponseBody(), result.getResponseSizeBytes(), result.isResponseTruncated(),
                            result.getResponseContentType());
            return new ResultDetail(result.getId(), result.getTestRunId(), result.getTestCaseId(), snapshotName(result),
                    result.getOperationKey(), result.getOutcome(), result.getRequestSnapshot(), response,
                    result.getChecks(), result.getErrorKind(), result.getErrorMessage(), result.getDurationMs(),
                    result.getTimeToHeadersMs(), result.getCorrelationId(), result.getTestCaseSnapshot(),
                    result.getExecutedAt());
        }
    }

    public record ResponseView(int status, Map<String, Object> headers, String body, Long sizeBytes,
            boolean truncated, String contentType) {
    }

    private static String snapshotName(TestResult result) {
        Object name = result.getTestCaseSnapshot().get("name");
        return name instanceof String value ? value : null;
    }

    /** How a test's outcome changed between a baseline run and a later run. */
    public enum ChangeKind {
        /** Not passed before, passed now. */
        FIXED,
        /** Passed before, not passed now. */
        REGRESSED,
        /** Another outcome change (e.g. ERROR to FAILED). */
        CHANGED,
        UNCHANGED,
        /** Only in the later run. */
        NEW,
        /** Only in the baseline run. */
        REMOVED
    }

    public record Change(String testName, String operationKey, UUID testCaseId, TestResult.Outcome baselineOutcome,
            TestResult.Outcome currentOutcome, ChangeKind kind, UUID baselineResultId, UUID currentResultId) {
    }

    public record RunComparison(RunView baseline, RunView current, Map<ChangeKind, Integer> counts, List<Change> changes) {
    }

    static String name(TestResult result) {
        return snapshotName(result);
    }
}

