package com.apiforge.execution.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/**
 * The outcome of executing one test in a run. Immutable history (a database trigger rejects
 * updates). Request and response data are stored only after secrets have been redacted.
 */
@Entity
@Immutable
@Table(name = "test_results")
public class TestResult {

    /** See docs/ARCHITECTURE.md section 5: inconclusive and skipped are never counted as passed. */
    public enum Outcome {
        PASSED, FAILED, INCONCLUSIVE, ERROR, SKIPPED
    }

    public enum ErrorKind {
        TIMEOUT, CONNECT_FAILED, DNS_FAILURE, TLS_ERROR, BLOCKED_BY_POLICY, RESPONSE_TOO_LARGE,
        INVALID_RESPONSE, REDIRECT_BLOCKED, VARIABLE_UNRESOLVED, CANCELLED, INTERNAL
    }

    /** Response data of an execution that received an HTTP response. */
    /** @param timeToHeadersMs until the response headers arrived, or {@code null} when unknown */
    public record ResponseData(int status, Map<String, Object> headers, String body, long sizeBytes,
            boolean truncated, String contentType, Integer timeToHeadersMs) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "test_run_id", nullable = false)
    private UUID testRunId;

    @Column(name = "test_case_id")
    private UUID testCaseId;

    @Column(name = "operation_key", nullable = false, length = 600)
    private String operationKey;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "test_case_snapshot", nullable = false)
    private Map<String, Object> testCaseSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_snapshot", nullable = false)
    private Map<String, Object> requestSnapshot;

    @Column(name = "response_status")
    private Integer responseStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_headers")
    private Map<String, Object> responseHeaders;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "response_size_bytes")
    private Long responseSizeBytes;

    @Column(name = "response_truncated", nullable = false)
    private boolean responseTruncated;

    @Column(name = "response_content_type", length = 255)
    private String responseContentType;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "time_to_headers_ms")
    private Integer timeToHeadersMs;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 20)
    private Outcome outcome;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "checks", nullable = false)
    private List<Map<String, Object>> checks;

    @Enumerated(EnumType.STRING)
    @Column(name = "error_kind", length = 30)
    private ErrorKind errorKind;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "executed_at", nullable = false)
    private Instant executedAt;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    protected TestResult() {
    }

    private TestResult(UUID testRunId, UUID testCaseId, String operationKey, Map<String, Object> testCaseSnapshot,
            Map<String, Object> requestSnapshot, Outcome outcome, List<Map<String, Object>> checks,
            Integer durationMs, Instant executedAt) {
        this.testRunId = testRunId;
        this.testCaseId = testCaseId;
        this.operationKey = operationKey;
        this.testCaseSnapshot = Map.copyOf(testCaseSnapshot);
        this.requestSnapshot = Map.copyOf(requestSnapshot);
        this.outcome = outcome;
        this.checks = List.copyOf(checks);
        this.durationMs = durationMs;
        this.executedAt = executedAt;
    }

    /** A result for a request that received an HTTP response and was evaluated. */
    public static TestResult evaluated(UUID testRunId, UUID testCaseId, String operationKey,
            Map<String, Object> testCaseSnapshot, Map<String, Object> requestSnapshot, ResponseData response,
            Outcome outcome, List<Map<String, Object>> checks, int durationMs, Instant executedAt) {
        if (outcome == Outcome.ERROR) {
            throw new IllegalArgumentException("Use failed(...) for transport errors");
        }
        TestResult result = new TestResult(testRunId, testCaseId, operationKey, testCaseSnapshot, requestSnapshot,
                outcome, checks, durationMs, executedAt);
        result.responseStatus = response.status();
        result.responseHeaders = Map.copyOf(response.headers());
        result.responseBody = response.body();
        result.responseSizeBytes = response.sizeBytes();
        result.responseTruncated = response.truncated();
        result.responseContentType = response.contentType();
        result.timeToHeadersMs = response.timeToHeadersMs();
        return result;
    }

    /** A result for an execution that ended without a usable response. */
    public static TestResult failed(UUID testRunId, UUID testCaseId, String operationKey,
            Map<String, Object> testCaseSnapshot, Map<String, Object> requestSnapshot, ErrorKind errorKind,
            String errorMessage, Integer durationMs, Instant executedAt) {
        TestResult result = new TestResult(testRunId, testCaseId, operationKey, testCaseSnapshot, requestSnapshot,
                Outcome.ERROR, List.of(), durationMs, executedAt);
        result.errorKind = errorKind;
        result.errorMessage = errorMessage;
        return result;
    }

    /** A test that was not executed (policy, cancellation); {@code reason} says why. */
    public static TestResult skipped(UUID testRunId, UUID testCaseId, String operationKey,
            Map<String, Object> testCaseSnapshot, Map<String, Object> requestSnapshot, String reason, Instant executedAt) {
        TestResult result = new TestResult(testRunId, testCaseId, operationKey, testCaseSnapshot, requestSnapshot,
                Outcome.SKIPPED, List.of(), null, executedAt);
        result.errorMessage = reason;
        return result;
    }

    /** Sets the id sent to the target as the correlation header (before the result is stored). */
    public TestResult correlatedWith(String id) {
        this.correlationId = id;
        return this;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTestRunId() {
        return testRunId;
    }

    public UUID getTestCaseId() {
        return testCaseId;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public Map<String, Object> getTestCaseSnapshot() {
        return testCaseSnapshot;
    }

    public Map<String, Object> getRequestSnapshot() {
        return requestSnapshot;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public Map<String, Object> getResponseHeaders() {
        return responseHeaders;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Long getResponseSizeBytes() {
        return responseSizeBytes;
    }

    public boolean isResponseTruncated() {
        return responseTruncated;
    }

    public String getResponseContentType() {
        return responseContentType;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public Integer getTimeToHeadersMs() {
        return timeToHeadersMs;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public List<Map<String, Object>> getChecks() {
        return checks;
    }

    public ErrorKind getErrorKind() {
        return errorKind;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }
}
