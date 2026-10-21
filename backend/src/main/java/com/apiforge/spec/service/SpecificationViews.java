package com.apiforge.spec.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.SpecificationVersion;

/** API representations of imported specifications and their operations. */
public final class SpecificationViews {

    private SpecificationViews() {
    }

    public record VersionSummary(UUID id, int versionNumber, String title, String apiVersion, String openapiVersion,
            SpecificationVersion.SourceFormat sourceFormat, String sourceName, int operationCount, int warningCount,
            boolean active, String contentSha256, int contentSizeBytes, Instant importedAt) {

        static VersionSummary from(SpecificationVersion version, UUID activeVersionId) {
            return new VersionSummary(version.getId(), version.getVersionNumber(), version.getTitle(),
                    version.getApiVersion(), version.getOpenapiVersion(), version.getSourceFormat(),
                    version.getSourceName(), version.getOperationCount(), countWarnings(version),
                    version.getId().equals(activeVersionId), version.getContentSha256(),
                    version.getContentSizeBytes(), version.getImportedAt());
        }

        private static int countWarnings(SpecificationVersion version) {
            return (int) version.getImportMessages().stream()
                    .filter(message -> "WARNING".equals(message.get("severity")))
                    .count();
        }
    }

    public record VersionDetail(VersionSummary summary, String description, List<Map<String, Object>> servers,
            Map<String, Object> securitySchemes, List<Map<String, Object>> tags, List<Map<String, Object>> messages) {

        static VersionDetail from(SpecificationVersion version, UUID activeVersionId) {
            return new VersionDetail(VersionSummary.from(version, activeVersionId), version.getDescription(),
                    version.getServers(), version.getSecuritySchemes(), version.getTags(),
                    version.getImportMessages());
        }
    }

    /** Result of an import: the new version plus every warning and note produced. */
    public record ImportResult(VersionSummary version, List<Map<String, Object>> messages) {
    }

    /** Result of a dry run. {@code valid=false} lists the errors that would reject the document. */
    public record ValidationResult(boolean valid, String openapiVersion, String title, String apiVersion,
            Integer operationCount, List<Map<String, Object>> messages) {
    }

    public record OperationSummary(UUID id, String operationKey, String method, String path, String operationId,
            String summary, List<String> tags, boolean deprecated, int warningCount) {

        static OperationSummary from(ApiOperation operation) {
            return new OperationSummary(operation.getId(), operation.getOperationKey(), operation.getMethod(),
                    operation.getPath(), operation.getOperationId(), operation.getSummary(), operation.getTags(),
                    operation.isDeprecated(), operation.getWarnings().size());
        }
    }

    public record OperationDetail(UUID id, UUID specificationVersionId, String operationKey, String method,
            String path, String operationId, String summary, String description, List<String> tags,
            boolean deprecated, List<Map<String, Object>> parameters, Map<String, Object> requestBody,
            List<Map<String, Object>> responses, List<Map<String, Object>> security,
            List<Map<String, Object>> warnings) {

        static OperationDetail from(ApiOperation operation) {
            return new OperationDetail(operation.getId(), operation.getSpecificationVersionId(),
                    operation.getOperationKey(), operation.getMethod(), operation.getPath(),
                    operation.getOperationId(), operation.getSummary(), operation.getDescription(),
                    operation.getTags(), operation.isDeprecated(), operation.getParameters(),
                    operation.getRequestBody(), operation.getResponses(), operation.getSecurity(),
                    operation.getWarnings());
        }
    }

    /** The original document exactly as imported. */
    public record RawSpecification(String content, SpecificationVersion.SourceFormat format, int versionNumber) {
    }
}
