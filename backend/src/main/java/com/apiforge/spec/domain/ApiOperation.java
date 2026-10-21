package com.apiforge.spec.domain;

import java.util.List;
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

/**
 * One HTTP method on one path of an imported specification version. {@code operationKey}
 * ("GET /pets/{petId}") is the identity that carries tests across versions.
 */
@Entity
@Immutable
@Table(name = "api_operations")
public class ApiOperation {

    /** Normalized content of one operation, as extracted at import time. */
    public record Contents(String method, String path, String operationId, String summary, String description,
            List<String> tags, boolean deprecated, List<Map<String, Object>> parameters,
            Map<String, Object> requestBody, List<Map<String, Object>> responses,
            List<Map<String, Object>> security, List<Map<String, Object>> warnings) {

        public String operationKey() {
            return method + " " + path;
        }
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "specification_version_id", nullable = false)
    private UUID specificationVersionId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "operation_key", nullable = false, length = 600)
    private String operationKey;

    @Column(name = "method", nullable = false, length = 10)
    private String method;

    @Column(name = "path", nullable = false, length = 500)
    private String path;

    @Column(name = "operation_id", length = 255)
    private String operationId;

    @Column(name = "summary", length = 1000)
    private String summary;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<String> tags;

    @Column(name = "deprecated", nullable = false)
    private boolean deprecated;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false)
    private List<Map<String, Object>> parameters;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_body")
    private Map<String, Object> requestBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "responses", nullable = false)
    private List<Map<String, Object>> responses;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "security", nullable = false)
    private List<Map<String, Object>> security;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "warnings", nullable = false)
    private List<Map<String, Object>> warnings;

    protected ApiOperation() {
    }

    public ApiOperation(UUID specificationVersionId, int sortOrder, Contents contents) {
        this.specificationVersionId = specificationVersionId;
        this.sortOrder = sortOrder;
        this.operationKey = contents.operationKey();
        this.method = contents.method();
        this.path = contents.path();
        this.operationId = contents.operationId();
        this.summary = contents.summary();
        this.description = contents.description();
        this.tags = List.copyOf(contents.tags());
        this.deprecated = contents.deprecated();
        this.parameters = List.copyOf(contents.parameters());
        this.requestBody = contents.requestBody() == null ? null : Map.copyOf(contents.requestBody());
        this.responses = List.copyOf(contents.responses());
        this.security = List.copyOf(contents.security());
        this.warnings = List.copyOf(contents.warnings());
    }

    public UUID getId() {
        return id;
    }

    public UUID getSpecificationVersionId() {
        return specificationVersionId;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getOperationKey() {
        return operationKey;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public String getOperationId() {
        return operationId;
    }

    public String getSummary() {
        return summary;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getTags() {
        return tags;
    }

    public boolean isDeprecated() {
        return deprecated;
    }

    public List<Map<String, Object>> getParameters() {
        return parameters;
    }

    public Map<String, Object> getRequestBody() {
        return requestBody;
    }

    public List<Map<String, Object>> getResponses() {
        return responses;
    }

    public List<Map<String, Object>> getSecurity() {
        return security;
    }

    public List<Map<String, Object>> getWarnings() {
        return warnings;
    }
}
