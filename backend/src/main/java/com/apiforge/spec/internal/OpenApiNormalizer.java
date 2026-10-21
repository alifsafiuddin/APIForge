package com.apiforge.spec.internal;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.ImportMessage;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.headers.Header;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;

/**
 * Converts the parsed model into the JSON stored per version and per operation. References to
 * parameters, request bodies, responses and headers are resolved so each operation is
 * self-contained; schema references are kept as {@code $ref} (resolved by clients against the
 * stored component schemas), which keeps recursive schemas finite and storage small.
 */
final class OpenApiNormalizer {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final int MAX_REF_HOPS = 10;

    private final ObjectMapper mapper;
    private final Components components;

    OpenApiNormalizer(OpenAPI openApi, boolean openapi31) {
        this.mapper = openapi31 ? Json31.mapper() : Json.mapper();
        this.components = openApi.getComponents() == null ? new Components() : openApi.getComponents();
    }

    Map<String, Object> toMap(Object value) {
        return value == null ? null : mapper.convertValue(value, MAP);
    }

    List<Map<String, Object>> toMaps(List<?> values) {
        return values == null ? List.of() : values.stream().map(this::toMap).toList();
    }

    Map<String, Object> mapOfMaps(Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((name, value) -> result.put(name, toMap(value)));
        }
        return result;
    }

    Map<String, Object> componentSchemas() {
        return mapOfMaps(components.getSchemas());
    }

    Map<String, Object> securitySchemes() {
        return mapOfMaps(components.getSecuritySchemes());
    }

    /** Operations in document order (paths as written, methods in OpenAPI order). */
    List<ApiOperation.Contents> operations(OpenAPI openApi, Function<String, List<ImportMessage>> warningsByKey) {
        List<ApiOperation.Contents> operations = new ArrayList<>();
        if (openApi.getPaths() == null) {
            return operations;
        }
        openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
            String httpMethod = method.name().toUpperCase(Locale.ROOT);
            String key = httpMethod + " " + path;
            operations.add(new ApiOperation.Contents(
                    httpMethod,
                    path,
                    operation.getOperationId(),
                    operation.getSummary(),
                    operation.getDescription(),
                    operation.getTags() == null ? List.of() : List.copyOf(operation.getTags()),
                    Boolean.TRUE.equals(operation.getDeprecated()),
                    parameters(pathItem, operation),
                    requestBody(operation.getRequestBody()),
                    responses(operation),
                    security(openApi, operation),
                    warningsByKey.apply(key).stream().map(ImportMessage::toMap).toList()));
        }));
        return operations;
    }

    /** Path-level parameters merged with operation-level ones; the operation wins on (name, in). */
    List<Parameter> effectiveParameters(PathItem pathItem, Operation operation) {
        Map<String, Parameter> byKey = new LinkedHashMap<>();
        for (List<Parameter> source : Arrays.asList(pathItem.getParameters(), operation.getParameters())) {
            if (source == null) {
                continue;
            }
            for (Parameter parameter : source) {
                Parameter resolved = resolveParameter(parameter);
                byKey.put(resolved.getIn() + ":" + resolved.getName(), resolved);
            }
        }
        return List.copyOf(byKey.values());
    }

    private List<Map<String, Object>> parameters(PathItem pathItem, Operation operation) {
        return effectiveParameters(pathItem, operation).stream().map(this::toMap).toList();
    }

    private Map<String, Object> requestBody(RequestBody requestBody) {
        RequestBody resolved = requestBody;
        for (int hop = 0; resolved != null && resolved.get$ref() != null && hop < MAX_REF_HOPS; hop++) {
            resolved = lookup(components.getRequestBodies(), resolved.get$ref(), "requestBodies");
        }
        return resolved == null ? null : toMap(resolved);
    }

    private List<Map<String, Object>> responses(Operation operation) {
        if (operation.getResponses() == null) {
            return List.of();
        }
        List<Map<String, Object>> responses = new ArrayList<>();
        operation.getResponses().forEach((status, response) -> {
            ApiResponse resolved = resolveResponse(response);
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", status);
            if (resolved == null) {
                map.put("description", "(unresolved reference " + response.get$ref() + ")");
            } else {
                Map<String, Object> body = toMap(resolved);
                if (resolved.getHeaders() != null) {
                    Map<String, Object> headers = new LinkedHashMap<>();
                    resolved.getHeaders().forEach((name, header) -> headers.put(name, toMap(resolveHeader(header))));
                    body.put("headers", headers);
                }
                map.putAll(body);
            }
            responses.add(map);
        });
        responses.sort(Comparator.comparing(response -> statusSortKey((String) response.get("status"))));
        return responses;
    }

    private List<Map<String, Object>> security(OpenAPI openApi, Operation operation) {
        // An explicit empty list on the operation means "no authentication"; absence inherits the global setting.
        return toMaps(operation.getSecurity() != null ? operation.getSecurity() : openApi.getSecurity());
    }

    Parameter resolveParameter(Parameter parameter) {
        Parameter resolved = parameter;
        for (int hop = 0; resolved != null && resolved.get$ref() != null && hop < MAX_REF_HOPS; hop++) {
            resolved = lookup(components.getParameters(), resolved.get$ref(), "parameters");
        }
        return resolved == null ? parameter : resolved;
    }

    private ApiResponse resolveResponse(ApiResponse response) {
        ApiResponse resolved = response;
        for (int hop = 0; resolved != null && resolved.get$ref() != null && hop < MAX_REF_HOPS; hop++) {
            resolved = lookup(components.getResponses(), resolved.get$ref(), "responses");
        }
        return resolved;
    }

    private Header resolveHeader(Header header) {
        Header resolved = header;
        for (int hop = 0; resolved != null && resolved.get$ref() != null && hop < MAX_REF_HOPS; hop++) {
            resolved = lookup(components.getHeaders(), resolved.get$ref(), "headers");
        }
        return resolved == null ? header : resolved;
    }

    private static <T> T lookup(Map<String, T> map, String ref, String section) {
        String prefix = "#/components/" + section + "/";
        if (map == null || ref == null || !ref.startsWith(prefix)) {
            return null;
        }
        String name = ref.substring(prefix.length()).replace("~1", "/").replace("~0", "~");
        return map.get(name);
    }

    /** 1XX/2XX classes and numeric codes in order, "default" last. */
    static String statusSortKey(String status) {
        if ("default".equalsIgnoreCase(status)) {
            return "9";
        }
        return status.toUpperCase(Locale.ROOT).replace('X', '0') + (status.toUpperCase(Locale.ROOT).contains("X") ? "1" : "0");
    }
}
