package com.apiforge.ai.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** A model's explanation of a failed result (its validated answer, never the prompt). */
@Entity
@Immutable
@Table(name = "ai_explanations")
public class AiExplanation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "test_result_id", nullable = false, updatable = false)
    private UUID testResultId;

    @Column(name = "model", nullable = false, length = 200, updatable = false)
    private String model;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "explanation", nullable = false, updatable = false)
    private Map<String, Object> explanation;

    @Column(name = "latency_ms", nullable = false, updatable = false)
    private int latencyMs;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiExplanation() {
    }

    public AiExplanation(UUID projectId, UUID testResultId, String model, Map<String, Object> explanation, int latencyMs,
            UUID createdBy, Instant createdAt) {
        this.projectId = projectId;
        this.testResultId = testResultId;
        this.model = model;
        this.explanation = Map.copyOf(explanation);
        this.latencyMs = latencyMs;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getTestResultId() {
        return testResultId;
    }

    public String getModel() {
        return model;
    }

    public Map<String, Object> getExplanation() {
        return explanation;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
