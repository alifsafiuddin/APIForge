package com.apiforge.testcase.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.apiforge.common.json.JsonValues;
import com.apiforge.common.persistence.AuditableEntity;

/**
 * A declarative test: request data plus expectations for one operation. There is no script or
 * expression field, so neither users nor AI suggestions can introduce executable code.
 */
@Entity
@Table(name = "test_cases")
public class TestCase extends AuditableEntity {

    public enum Category {
        HAPPY_PATH, MISSING_FIELD, INVALID_TYPE, BOUNDARY_VALUE, ENUM_VALUE, STRING_LENGTH, ARRAY_LENGTH,
        QUERY_PARAMETER, PATH_PARAMETER, HEADER, CONTENT_TYPE, RESPONSE_VALIDATION, AUTH, CUSTOM
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

    /** Everything the deterministic generator decides about one scenario. */
    public record Definition(String operationKey, String scenarioKey, String name, Category category,
            RiskLevel riskLevel, RequestTemplate request, Expectations expectations, String rationale,
            String specLocation, int sortOrder, String generatorVersion) {

        public String dedupeHash() {
            return TestCase.dedupeHash(operationKey, scenarioKey);
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

    /** The model that suggested this test ({@link Origin#AI_SUGGESTED} only). */
    @Column(name = "ai_model", length = 200)
    private String aiModel;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "dedupe_hash", nullable = false, length = 64)
    private String dedupeHash;

    @Column(name = "scenario_key", length = 300)
    private String scenarioKey;

    @Column(name = "spec_location", length = 1000)
    private String specLocation;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "user_modified", nullable = false)
    private boolean userModified;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

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

    /** A new deterministic test in {@code suiteId}, generated from {@code sourceVersionId}. */
    public static TestCase generated(UUID projectId, UUID suiteId, UUID sourceVersionId, Definition definition) {
        TestCase test = new TestCase(projectId, suiteId, definition.operationKey(), definition.name(),
                definition.category(), Origin.DETERMINISTIC, definition.riskLevel(), definition.request().toMap(),
                definition.expectations().toMap(), definition.dedupeHash());
        test.apply(definition, sourceVersionId);
        return test;
    }

    /**
     * A test suggested by a model: data only (a request template and expectations), stored pending
     * review, so it cannot run until a person approves it.
     *
     * @param promptVersion version of the prompt that produced it (stored as the generator version)
     */
    public static TestCase suggested(UUID projectId, UUID suiteId, UUID sourceVersionId, String operationKey,
            String name, Category category, RequestTemplate request, Expectations expectations, String rationale,
            String model, String promptVersion, String dedupeHash, int sortOrder) {
        TestCase test = new TestCase(projectId, suiteId, operationKey, name, category, Origin.AI_SUGGESTED,
                RiskLevel.forMethod(operationKey.substring(0, operationKey.indexOf(' '))), request.toMap(),
                expectations.toMap(), dedupeHash);
        test.rationale = rationale;
        test.aiModel = model;
        test.generatorVersion = promptVersion;
        test.sourceSpecificationVersionId = sourceVersionId;
        test.sortOrder = sortOrder;
        return test;
    }

    /**
     * Identity of a generated scenario. It depends only on the operation and the scenario key, not on
     * generated values, so regenerating from a changed document updates the existing test in place.
     */
    public static String dedupeHash(String operationKey, String scenarioKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("deterministic\n" + operationKey + "\n" + scenarioKey).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }

    /** Whether the stored content already equals {@code definition} (values compared as JSON). */
    public boolean matches(Definition definition) {
        return name.equals(definition.name())
                && category == definition.category()
                && riskLevel == definition.riskLevel()
                && sortOrder == definition.sortOrder()
                && Objects.equals(rationale, definition.rationale())
                && Objects.equals(specLocation, definition.specLocation())
                && Objects.equals(generatorVersion, definition.generatorVersion())
                && JsonValues.jsonEquals(requestTemplate, definition.request().toMap())
                && JsonValues.jsonEquals(expectations, definition.expectations().toMap());
    }

    /** Replaces the generated content; user choices (enabled, suite, review) are kept. */
    public void refresh(Definition definition, UUID sourceVersionId) {
        this.name = definition.name();
        this.category = definition.category();
        this.riskLevel = definition.riskLevel();
        this.requestTemplate = definition.request().toMap();
        this.expectations = definition.expectations().toMap();
        apply(definition, sourceVersionId);
    }

    private void apply(Definition definition, UUID sourceVersionId) {
        this.rationale = definition.rationale();
        this.specLocation = definition.specLocation();
        this.sortOrder = definition.sortOrder();
        this.generatorVersion = definition.generatorVersion();
        this.scenarioKey = definition.scenarioKey();
        this.sourceSpecificationVersionId = sourceVersionId;
    }

    /** Records that an unchanged scenario was confirmed against {@code sourceVersionId}. */
    public void confirmSource(UUID sourceVersionId) {
        this.sourceSpecificationVersionId = sourceVersionId;
    }

    /**
     * A user edit. The test is marked as modified, so regeneration no longer overwrites it; an
     * edited AI suggestion needs a new review.
     */
    public void edit(String newName, RequestTemplate request, Expectations newExpectations, String newRationale) {
        this.name = newName;
        this.requestTemplate = request.toMap();
        this.expectations = newExpectations.toMap();
        this.rationale = newRationale;
        this.userModified = true;
        if (origin == Origin.AI_SUGGESTED) {
            this.reviewStatus = ReviewStatus.PENDING_REVIEW;
            this.reviewedBy = null;
            this.reviewedAt = null;
        }
    }

    public void review(ReviewStatus decision, UUID reviewerId, Instant now) {
        this.reviewStatus = decision;
        this.reviewedBy = reviewerId;
        this.reviewedAt = now;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void moveTo(UUID targetSuiteId) {
        this.suiteId = targetSuiteId;
    }

    /** Soft delete: kept for history, excluded from listings, runs and the dedupe index. */
    public void archive() {
        this.lifecycleStatus = LifecycleStatus.ARCHIVED;
    }

    public boolean isExecutable() {
        return enabled && reviewStatus == ReviewStatus.APPROVED && lifecycleStatus == LifecycleStatus.ACTIVE;
    }

    public RequestTemplate getRequest() {
        return RequestTemplate.fromMap(requestTemplate);
    }

    public Expectations getExpectationsValue() {
        return Expectations.fromMap(expectations);
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

    public String getAiModel() {
        return aiModel;
    }

    public String getDedupeHash() {
        return dedupeHash;
    }

    public String getScenarioKey() {
        return scenarioKey;
    }

    public String getSpecLocation() {
        return specLocation;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isUserModified() {
        return userModified;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }
}
