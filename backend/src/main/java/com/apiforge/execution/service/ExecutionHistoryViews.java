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

    public record RunView(UUID id, UUID projectId, TestRun.Status status, UUID environmentId, String environmentName,
            String baseUrl, UUID suiteId, UUID specificationVersionId, UUID triggeredBy, OutcomeCounts counts,
            Instant createdAt, Instant startedAt, Instant finishedAt) {

        static RunView from(TestRun run) {
            return new RunView(run.getId(), run.getProjectId(), run.getStatus(), run.getEnvironmentId(),
                    run.getEnvironmentNameSnapshot(), run.getBaseUrlSnapshot(), run.getSuiteId(),
                    run.getSpecificationVersionId(), run.getTriggeredBy(),
                    new OutcomeCounts(run.getTotalCount(), run.getPassedCount(), run.getFailedCount(),
                            run.getInconclusiveCount(), run.getErrorCount(), run.getSkippedCount()),
                    run.getCreatedAt(), run.getStartedAt(), run.getFinishedAt());
        }
    }

    public record ResultSummary(UUID id, UUID testCaseId, String testName, String operationKey,
            TestResult.Outcome outcome, Integer responseStatus, Integer durationMs, TestResult.ErrorKind errorKind,
            Instant executedAt) {

        static ResultSummary from(TestResult result) {
            return new ResultSummary(result.getId(), result.getTestCaseId(), snapshotName(result),
                    result.getOperationKey(), result.getOutcome(), result.getResponseStatus(),
                    result.getDurationMs(), result.getErrorKind(), result.getExecutedAt());
        }
    }

    /** Full detail. Request and response data were redacted before they were stored. */
    public record ResultDetail(UUID id, UUID testRunId, UUID testCaseId, String testName, String operationKey,
            TestResult.Outcome outcome, Map<String, Object> request, ResponseView response,
            List<Map<String, Object>> checks, TestResult.ErrorKind errorKind, String errorMessage, Integer durationMs,
            Instant executedAt) {

        static ResultDetail from(TestResult result) {
            ResponseView response = result.getResponseStatus() == null ? null
                    : new ResponseView(result.getResponseStatus(), result.getResponseHeaders(),
                            result.getResponseBody(), result.getResponseSizeBytes(), result.isResponseTruncated(),
                            result.getResponseContentType());
            return new ResultDetail(result.getId(), result.getTestRunId(), result.getTestCaseId(), snapshotName(result),
                    result.getOperationKey(), result.getOutcome(), result.getRequestSnapshot(), response,
                    result.getChecks(), result.getErrorKind(), result.getErrorMessage(), result.getDurationMs(),
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
}
