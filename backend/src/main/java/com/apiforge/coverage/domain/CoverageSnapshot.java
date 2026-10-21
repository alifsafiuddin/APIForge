package com.apiforge.coverage.domain;

import java.time.Instant;
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

/** The summary numbers of a project's coverage at one point in time (immutable history). */
@Entity
@Immutable
@Table(name = "coverage_snapshots")
public class CoverageSnapshot {

    public enum Trigger {
        TESTS_GENERATED, RUN_FINISHED, VERSION_ACTIVATED
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "specification_version_id", nullable = false, updatable = false)
    private UUID specificationVersionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger", nullable = false, length = 30, updatable = false)
    private Trigger trigger;

    @Column(name = "test_run_id", updatable = false)
    private UUID testRunId;

    @Column(name = "taken_at", nullable = false, updatable = false)
    private Instant takenAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metrics", nullable = false, updatable = false)
    private Map<String, Integer> metrics;

    protected CoverageSnapshot() {
    }

    public CoverageSnapshot(UUID projectId, UUID specificationVersionId, Trigger trigger, UUID testRunId, Instant takenAt,
            Map<String, Integer> metrics) {
        if ((trigger == Trigger.RUN_FINISHED) != (testRunId != null)) {
            throw new IllegalArgumentException("A run is named exactly for RUN_FINISHED snapshots");
        }
        this.projectId = projectId;
        this.specificationVersionId = specificationVersionId;
        this.trigger = trigger;
        this.testRunId = testRunId;
        this.takenAt = takenAt;
        this.metrics = Map.copyOf(metrics);
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSpecificationVersionId() {
        return specificationVersionId;
    }

    public Trigger getTrigger() {
        return trigger;
    }

    public UUID getTestRunId() {
        return testRunId;
    }

    public Instant getTakenAt() {
        return takenAt;
    }

    public Map<String, Integer> getMetrics() {
        return metrics;
    }
}
