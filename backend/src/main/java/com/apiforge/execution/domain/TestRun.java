package com.apiforge.execution.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.UuidGenerator;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One execution of a set of tests in an environment. The environment name and base URL are
 * copied at creation so later edits to the environment never change history.
 */
@Entity
@Table(name = "test_runs")
@EntityListeners(AuditingEntityListener.class)
public class TestRun {

    public enum Status {
        QUEUED, RUNNING, COMPLETED, CANCELLED, INTERRUPTED
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "environment_id")
    private UUID environmentId;

    @Column(name = "suite_id")
    private UUID suiteId;

    @Column(name = "specification_version_id")
    private UUID specificationVersionId;

    @Column(name = "triggered_by")
    private UUID triggeredBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status = Status.QUEUED;

    @Column(name = "environment_name_snapshot", length = 100)
    private String environmentNameSnapshot;

    @Column(name = "base_url_snapshot", length = 2048)
    private String baseUrlSnapshot;

    @Column(name = "total_count", nullable = false)
    private int totalCount;

    @Column(name = "passed_count", nullable = false)
    private int passedCount;

    @Column(name = "failed_count", nullable = false)
    private int failedCount;

    @Column(name = "inconclusive_count", nullable = false)
    private int inconclusiveCount;

    @Column(name = "error_count", nullable = false)
    private int errorCount;

    @Column(name = "skipped_count", nullable = false)
    private int skippedCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected TestRun() {
    }

    public TestRun(UUID projectId, UUID environmentId, String environmentNameSnapshot, String baseUrlSnapshot,
            UUID triggeredBy, int totalCount) {
        this.projectId = projectId;
        this.environmentId = environmentId;
        this.environmentNameSnapshot = environmentNameSnapshot;
        this.baseUrlSnapshot = baseUrlSnapshot;
        this.triggeredBy = triggeredBy;
        this.totalCount = totalCount;
    }

    public void start(Instant now) {
        status = Status.RUNNING;
        startedAt = now;
    }

    public void record(TestResult.Outcome outcome) {
        switch (outcome) {
            case PASSED -> passedCount++;
            case FAILED -> failedCount++;
            case INCONCLUSIVE -> inconclusiveCount++;
            case ERROR -> errorCount++;
            case SKIPPED -> skippedCount++;
        }
    }

    public void finish(Status finalStatus, Instant now) {
        status = finalStatus;
        finishedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getEnvironmentId() {
        return environmentId;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public UUID getSpecificationVersionId() {
        return specificationVersionId;
    }

    public UUID getTriggeredBy() {
        return triggeredBy;
    }

    public Status getStatus() {
        return status;
    }

    public String getEnvironmentNameSnapshot() {
        return environmentNameSnapshot;
    }

    public String getBaseUrlSnapshot() {
        return baseUrlSnapshot;
    }

    public int getTotalCount() {
        return totalCount;
    }

    public int getPassedCount() {
        return passedCount;
    }

    public int getFailedCount() {
        return failedCount;
    }

    public int getInconclusiveCount() {
        return inconclusiveCount;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public int getSkippedCount() {
        return skippedCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
