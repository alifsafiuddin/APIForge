package com.apiforge.spec.domain;

import java.util.List;
import java.util.Map;

/**
 * A document that passed every import check, normalized and ready to persist.
 * {@code messages} contains only warnings and informational notes.
 */
public record AnalyzedSpecification(
        String openapiVersion,
        String title,
        String apiVersion,
        String description,
        SpecificationVersion.SourceFormat sourceFormat,
        String rawContent,
        String contentSha256,
        int contentSizeBytes,
        List<Map<String, Object>> servers,
        Map<String, Object> securitySchemes,
        List<Map<String, Object>> tags,
        Map<String, Object> componentSchemas,
        List<ApiOperation.Contents> operations,
        List<ImportMessage> messages) {

    public SpecificationVersion.Contents toVersionContents(String sourceName, List<ImportMessage> allMessages) {
        return new SpecificationVersion.Contents(openapiVersion, title, apiVersion, description, sourceFormat,
                sourceName, rawContent, contentSha256, contentSizeBytes, servers, securitySchemes, tags,
                componentSchemas, operations.size(), allMessages.stream().map(ImportMessage::toMap).toList());
    }
}
