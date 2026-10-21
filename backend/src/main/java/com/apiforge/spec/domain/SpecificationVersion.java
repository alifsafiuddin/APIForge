package com.apiforge.spec.domain;

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
 * An imported OpenAPI document. Immutable (also enforced by a database trigger): re-importing
 * creates a new version, so test history always refers to the exact contract it ran against.
 */
@Entity
@Immutable
@Table(name = "specification_versions")
public class SpecificationVersion {

    public enum SourceFormat {
        JSON, YAML
    }

    /** Everything known about an imported document, in one value to keep construction readable. */
    public record Contents(String openapiVersion, String title, String apiVersion, String description,
            SourceFormat sourceFormat, String sourceName, String rawContent, String contentSha256,
            int contentSizeBytes, List<Map<String, Object>> servers, Map<String, Object> securitySchemes,
            List<Map<String, Object>> tags, Map<String, Object> componentSchemas, int operationCount,
            List<Map<String, Object>> importMessages) {
    }

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(name = "openapi_version", nullable = false, length = 20)
    private String openapiVersion;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "api_version", length = 100)
    private String apiVersion;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_format", nullable = false, length = 10)
    private SourceFormat sourceFormat;

    @Column(name = "source_name", length = 255)
    private String sourceName;

    @Column(name = "raw_content", nullable = false, columnDefinition = "text")
    private String rawContent;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "content_sha256", nullable = false, length = 64)
    private String contentSha256;

    @Column(name = "content_size_bytes", nullable = false)
    private int contentSizeBytes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "servers", nullable = false)
    private List<Map<String, Object>> servers;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "security_schemes", nullable = false)
    private Map<String, Object> securitySchemes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false)
    private List<Map<String, Object>> tags;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "component_schemas", nullable = false)
    private Map<String, Object> componentSchemas;

    @Column(name = "operation_count", nullable = false)
    private int operationCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "import_messages", nullable = false)
    private List<Map<String, Object>> importMessages;

    @Column(name = "imported_by")
    private UUID importedBy;

    @Column(name = "imported_at", nullable = false)
    private Instant importedAt;

    protected SpecificationVersion() {
    }

    public SpecificationVersion(UUID projectId, int versionNumber, Contents contents, UUID importedBy,
            Instant importedAt) {
        this.projectId = projectId;
        this.versionNumber = versionNumber;
        this.openapiVersion = contents.openapiVersion();
        this.title = contents.title();
        this.apiVersion = contents.apiVersion();
        this.description = contents.description();
        this.sourceFormat = contents.sourceFormat();
        this.sourceName = contents.sourceName();
        this.rawContent = contents.rawContent();
        this.contentSha256 = contents.contentSha256();
        this.contentSizeBytes = contents.contentSizeBytes();
        this.servers = List.copyOf(contents.servers());
        this.securitySchemes = Map.copyOf(contents.securitySchemes());
        this.tags = List.copyOf(contents.tags());
        this.componentSchemas = Map.copyOf(contents.componentSchemas());
        this.operationCount = contents.operationCount();
        this.importMessages = List.copyOf(contents.importMessages());
        this.importedBy = importedBy;
        this.importedAt = importedAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public int getVersionNumber() {
        return versionNumber;
    }

    public String getOpenapiVersion() {
        return openapiVersion;
    }

    public String getTitle() {
        return title;
    }

    public String getApiVersion() {
        return apiVersion;
    }

    public String getDescription() {
        return description;
    }

    public SourceFormat getSourceFormat() {
        return sourceFormat;
    }

    public String getSourceName() {
        return sourceName;
    }

    public String getRawContent() {
        return rawContent;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public int getContentSizeBytes() {
        return contentSizeBytes;
    }

    public List<Map<String, Object>> getServers() {
        return servers;
    }

    public Map<String, Object> getSecuritySchemes() {
        return securitySchemes;
    }

    public List<Map<String, Object>> getTags() {
        return tags;
    }

    public Map<String, Object> getComponentSchemas() {
        return componentSchemas;
    }

    public int getOperationCount() {
        return operationCount;
    }

    public List<Map<String, Object>> getImportMessages() {
        return importMessages;
    }

    public UUID getImportedBy() {
        return importedBy;
    }

    public Instant getImportedAt() {
        return importedAt;
    }
}
