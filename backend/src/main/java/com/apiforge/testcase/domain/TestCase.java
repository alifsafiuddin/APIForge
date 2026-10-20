package com.apiforge.testcase.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.apiforge.common.persistence.AuditableEntity;

/**
 * A declarative test: request data plus expectations for one operation. There is no script or
 * expression field, so neither users nor AI suggestions can introduce executable code.
 */
@Entity
@Table(name = "test_cases")
public class TestCase extends AuditableEntity {

    public enum Category {
        HAPPY_PATH, REQUIRED_FIELD, MISSING_FIELD, INVALID_TYPE, BOUNDARY_VALUE, ENUM_VALUE,
        QUERY_PARAMETER, PATH_PARAMETER, HEADER, RESPONSE_VALIDATION, AUTH, CUSTOM
    }

    public enum Origin {
        DETERMINISTIC, AI_SUGGESTED, MANUAL
    }

    public enum ReviewStatus {
        APPROVED, PENDING_REVIEW, REJECTED
    }

    public enum LifecycleStatus {
        ACTIVE, STALE, ARCHIVED
    }

    /** Derived from the HTTP method; governs which environments may execute the test. */
    public enum RiskLevel {
        READ_ONLY, MUTATING, DESTRUCTIVE;

        public static RiskLevel forMethod(String method) {
            return switch (method.toUpperCase(java.util.Locale.ROOT)) {
                case "GET", "HEAD", "OPTIONS" -> READ_ONLY;
                case "DELETE" -> DESTRUCTIVE;
                default -> MUTATING;
            };
        }
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "suite_id", nullable = false)
    private UUID suiteId;

    @Column(name = "source_specification_version_id")
    private UUID sourceSpecificationVersionId;

    @Column(name = "operation_key", nullable = false, length = 600)
    private String operationKey;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30)
    private Category category;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20)
    private Origin origin;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 20)
    private ReviewStatus reviewStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "lifecycle_status", nullable = false, length = 20)
    private LifecycleStatus lifecycleStatus = LifecycleStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 20)
    private RiskLevel riskLevel;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_template", nullable = false)
    private Map<String, Object> requestTemplate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "expectations", nullable = false)
    private Map<String, Object> expectations;

    @Column(name = "rationale", columnDefinition = "text")
    private String rationale;

    @Column(name = "generator_version", length = 20)
    private String generatorVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dedupe_hash", nullable = false, length = 64)
    private String dedupeHash;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    protected TestCase() {
    }

    /**
     * AI suggestions start as {@link ReviewStatus#PENDING_REVIEW}; deterministic and manual tests
     * are approved at creation.
     */
    public TestCase(UUID projectId, UUID suiteId, String operationKey, String name, Category category, Origin origin,
            RiskLevel riskLevel, Map<String, Object> requestTemplate, Map<String, Object> expectations,
            String dedupeHash) {
        this.projectId = projectId;
        this.suiteId = suiteId;
        this.operationKey = operationKey;
        this.name = name;
        this.category = category;
        this.origin = origin;
        this.reviewStatus = origin == Origin.AI_SUGGESTED ? ReviewStatus.PENDING_REVIEW : ReviewStatus.APPROVED;
        this.riskLevel = riskLevel;
        this.requestTemplate = Map.copyOf(requestTemplate);
        this.expectations = Map.copyOf(expectations);
        this.dedupeHash = dedupeHash;
    }

    public void review(ReviewStatus decision, UUID reviewerId, Instant now) {
        this.reviewStatus = decision;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
    }

    public boolean isExecutable() {
        return reviewStatus == ReviewStatus.APPROVED && lifecycleStatus == LifecycleStatus.ACTIVE;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public UUID getSuiteId() {
        return suiteId;
    }

    public UUID getSourceSpecificationVersionId() {
        return sourceSpecificationVersionId;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public String getName() {
        return name;
    }

    public Category getCategory() {
        return category;
    }

    public Origin getOrigin() {
        return origin;
    }

    public ReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public LifecycleStatus getLifecycleStatus() {
        return lifecycleStatus;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }

    public Map<String, Object> getRequestTemplate() {
        return requestTemplate;
    }

    public Map<String, Object> getExpectations() {
        return expectations;
    }

    public String getRationale() {
        return rationale;
    }

    public String getGeneratorVersion() {
        return generatorVersion;
    }

    public String getDedupeHash() {
        return dedupeHash;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
