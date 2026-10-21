package com.apiforge.spec.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.apiforge.config.ApiForgeProperties;
import com.apiforge.spec.domain.AnalyzedSpecification;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.ImportMessage;
import com.apiforge.spec.domain.ImportMessage.Code;
import com.apiforge.spec.domain.SpecificationRejectedException;
import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * The import pipeline: read with limits → check the OpenAPI version → reject non-local
 * references → parse with swagger-parser → normalize → semantic checks. Never performs I/O
 * beyond the bytes it is given. Throws {@link SpecificationRejectedException} with readable
 * messages when the document cannot be imported.
 */
@Component
public class SpecificationAnalyzer {

    static final int MAX_PATH_LENGTH = 500;
    static final int MAX_MESSAGES = 200;

    private static final Pattern SUPPORTED_VERSION = Pattern.compile("^3\\.[01]\\.\\d+$");
    private static final Pattern PATH_TEMPLATE = Pattern.compile("\\{[^}/]+}");
    private static final Set<SecurityScheme.Type> INTERACTIVE_SCHEMES = Set.of(SecurityScheme.Type.OAUTH2,
            SecurityScheme.Type.OPENIDCONNECT, SecurityScheme.Type.MUTUALTLS);

    private final DocumentReader reader;
    private final int maxOperations;

    public SpecificationAnalyzer(ApiForgeProperties properties) {
        ApiForgeProperties.Specification limits = properties.specification();
        this.reader = new DocumentReader((int) limits.maxSize().toBytes(), limits.maxNestingDepth(),
                limits.maxYamlAliases());
        this.maxOperations = limits.maxOperations();
    }

    public AnalyzedSpecification analyze(byte[] content) {
        DocumentReader.ReadDocument document = reader.read(content);
        String openapiVersion = checkVersion(document.tree());

        List<ImportMessage> referenceProblems = ReferenceGuard.check(document.tree());
        if (!referenceProblems.isEmpty()) {
            throw new SpecificationRejectedException(referenceProblems);
        }

        OpenApiParserAdapter.Parsed parsed = OpenApiParserAdapter.parse(reader.toJson(document.tree()));
        List<ImportMessage> messages = new ArrayList<>(parsed.messages());
        OpenAPI openApi = parsed.openApi();
        if (openApi == null) {
            messages.addFirst(ImportMessage.error(Code.PARSE_ERROR,
                    "The document could not be interpreted as an OpenAPI description.", null));
            throw rejected(messages);
        }

        boolean openapi31 = openapiVersion.startsWith("3.1");
        OpenApiNormalizer normalizer = new OpenApiNormalizer(openApi, openapi31);
        Map<String, List<ImportMessage>> operationWarnings = new HashMap<>();
        messages.addAll(checkStructure(openApi, normalizer, maxOperations));
        messages.addAll(checkUnsupportedFeatures(openApi, openapi31, operationWarnings));
        if (messages.stream().anyMatch(ImportMessage::isError)) {
            throw rejected(messages);
        }

        List<ApiOperation.Contents> operations = normalizer.operations(openApi,
                key -> operationWarnings.getOrDefault(key, List.of()));

        byte[] canonical = document.text().getBytes(StandardCharsets.UTF_8);
        return new AnalyzedSpecification(
                openapiVersion,
                openApi.getInfo().getTitle(),
                openApi.getInfo().getVersion(),
                openApi.getInfo().getDescription(),
                document.format(),
                document.text(),
                sha256(canonical),
                canonical.length,
                normalizer.toMaps(openApi.getServers()),
                normalizer.securitySchemes(),
                normalizer.toMaps(openApi.getTags()),
                normalizer.componentSchemas(),
                operations,
                limit(messages));
    }

    private static String checkVersion(JsonNode tree) {
        JsonNode openapi = tree.get("openapi");
        if (openapi == null || openapi.isNull()) {
            if (tree.has("swagger")) {
                throw new SpecificationRejectedException(ImportMessage.error(Code.UNSUPPORTED_VERSION,
                        "Swagger 2.0 documents are not supported. Convert the document to OpenAPI 3 first (for example with swagger2openapi or editor.swagger.io).",
                        "swagger"));
            }
            throw new SpecificationRejectedException(ImportMessage.error(Code.MISSING_FIELD,
                    "Required field 'openapi' is missing. Is this an OpenAPI 3 document?", "openapi"));
        }
        String version = openapi.asText().strip();
        if (!SUPPORTED_VERSION.matcher(version).matches()) {
            throw new SpecificationRejectedException(ImportMessage.error(Code.UNSUPPORTED_VERSION,
                    "OpenAPI version '%s' is not supported. Supported: 3.0.x and 3.1.x (written in full, e.g. \"3.0.3\")."
                            .formatted(version.length() > 40 ? version.substring(0, 40) : version),
                    "openapi"));
        }
        return version;
    }

    /** Errors that make operations ambiguous or the document too large to handle. */
    static List<ImportMessage> checkStructure(OpenAPI openApi, OpenApiNormalizer normalizer, int maxOperations) {
        List<ImportMessage> messages = new ArrayList<>();
        if (openApi.getPaths() == null || openApi.getPaths().isEmpty()) {
            messages.add(ImportMessage.warning(Code.NO_OPERATIONS,
                    "The document declares no paths, so there is nothing to explore or test.", "paths"));
            return messages;
        }
        Map<String, String> operationIds = new HashMap<>();
        Map<String, String> templates = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, PathItem> entry : openApi.getPaths().entrySet()) {
            String path = entry.getKey();
            if (path.length() > MAX_PATH_LENGTH) {
                messages.add(ImportMessage.error(Code.LIMIT_EXCEEDED,
                        "Path is longer than %d characters.".formatted(MAX_PATH_LENGTH),
                        Locations.format(List.of("paths", path.substring(0, 60) + "…"))));
            }
            String template = PATH_TEMPLATE.matcher(path).replaceAll("{}");
            String clash = templates.putIfAbsent(template, path);
            if (clash != null) {
                messages.add(ImportMessage.error(Code.AMBIGUOUS_PATH,
                        "Paths '%s' and '%s' differ only in parameter names, so requests cannot be told apart."
                                .formatted(clash, path),
                        Locations.format(List.of("paths", path))));
            }
            for (Map.Entry<PathItem.HttpMethod, Operation> operation : entry.getValue().readOperationsMap().entrySet()) {
                count++;
                String location = Locations.operation(path, operation.getKey().name());
                messages.addAll(checkPathParameters(path, normalizer.effectiveParameters(entry.getValue(),
                        operation.getValue()), location));
                String operationId = operation.getValue().getOperationId();
                if (operationId == null) {
                    continue;
                }
                String first = operationIds.putIfAbsent(operationId, location);
                if (first != null) {
                    messages.add(ImportMessage.error(Code.DUPLICATE_OPERATION_ID,
                            "operationId '%s' is also used at %s; operationIds must be unique.".formatted(operationId,
                                    first),
                            location));
                }
            }
        }
        if (count > maxOperations) {
            messages.add(ImportMessage.error(Code.LIMIT_EXCEEDED,
                    "The document declares %d operations; the limit is %d.".formatted(count, maxOperations), "paths"));
        }
        return messages;
    }

    /** Every {name} in the path must be a declared path parameter (otherwise requests cannot be built). */
    private static List<ImportMessage> checkPathParameters(String path, List<Parameter> parameters, String location) {
        List<ImportMessage> messages = new ArrayList<>();
        Set<String> declared = new LinkedHashSet<>();
        for (Parameter parameter : parameters) {
            if ("path".equals(parameter.getIn()) && parameter.getName() != null) {
                declared.add(parameter.getName());
            }
        }
        Set<String> inTemplate = new LinkedHashSet<>();
        Matcher matcher = PATH_TEMPLATE.matcher(path);
        while (matcher.find()) {
            inTemplate.add(matcher.group().substring(1, matcher.group().length() - 1));
        }
        for (String name : inTemplate) {
            if (!declared.contains(name)) {
                messages.add(ImportMessage.error(Code.UNDECLARED_PATH_PARAMETER,
                        "Path parameter '%s' appears in the path but is not declared as a parameter (in: path)."
                                .formatted(name),
                        location));
            }
        }
        for (String name : declared) {
            if (!inTemplate.contains(name)) {
                messages.add(ImportMessage.warning(Code.INVALID_FIELD,
                        "Path parameter '%s' is declared but does not appear in the path; it is ignored.".formatted(name),
                        location));
            }
        }
        return messages;
    }

    /** Features that are documented but not (yet) usable for testing; reported, never silently dropped. */
    static List<ImportMessage> checkUnsupportedFeatures(OpenAPI openApi, boolean openapi31,
            Map<String, List<ImportMessage>> operationWarnings) {
        List<ImportMessage> messages = new ArrayList<>();
        if (openapi31) {
            messages.add(ImportMessage.info(Code.UNSUPPORTED_FEATURE,
                    "OpenAPI 3.1: import and exploration are fully supported. Contract-validation support for 3.1-specific schema keywords is reported per check once validation is available.",
                    "openapi"));
        }
        if (openApi.getServers() == null || openApi.getServers().isEmpty()
                || openApi.getServers().size() == 1 && "/".equals(openApi.getServers().getFirst().getUrl())) {
            messages.add(ImportMessage.info(Code.NO_SERVERS,
                    "No servers are declared. You will set the base URL in an environment.", "servers"));
        }
        if (openApi.getWebhooks() != null && !openApi.getWebhooks().isEmpty()) {
            messages.add(ImportMessage.warning(Code.UNSUPPORTED_FEATURE,
                    "Webhooks (%d) are shown for reference only; APIForge tests requests that it sends, not incoming webhooks."
                            .formatted(openApi.getWebhooks().size()),
                    "webhooks"));
        }
        if (openApi.getComponents() != null && openApi.getComponents().getSecuritySchemes() != null) {
            openApi.getComponents().getSecuritySchemes().forEach((name, scheme) -> {
                if (scheme.getType() != null && INTERACTIVE_SCHEMES.contains(scheme.getType())) {
                    messages.add(ImportMessage.warning(Code.UNSUPPORTED_FEATURE,
                            "Security scheme '%s' (%s) is not performed automatically. To test operations that require it, supply an access token through an environment (bearer authentication)."
                                    .formatted(name, scheme.getType()),
                            Locations.format(List.of("components", "securitySchemes", name))));
                }
            });
        }
        if (openApi.getPaths() == null) {
            return messages;
        }
        openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperationsMap().forEach((method, operation) -> {
            String key = method.name() + " " + path;
            String location = Locations.operation(path, method.name());
            List<ImportMessage> warnings = new ArrayList<>();
            if (operation.getCallbacks() != null && !operation.getCallbacks().isEmpty()) {
                warnings.add(ImportMessage.warning(Code.UNSUPPORTED_FEATURE,
                        "Callbacks are shown for reference only and are not tested.", location));
            }
            if (operation.getResponses() != null && operation.getResponses().values().stream()
                    .anyMatch(response -> response.getLinks() != null && !response.getLinks().isEmpty())) {
                warnings.add(ImportMessage.info(Code.UNSUPPORTED_FEATURE,
                        "Response links are shown for reference only and are not followed.", location));
            }
            List<Parameter> parameters = new ArrayList<>();
            if (pathItem.getParameters() != null) {
                parameters.addAll(pathItem.getParameters());
            }
            if (operation.getParameters() != null) {
                parameters.addAll(operation.getParameters());
            }
            if (parameters.stream().anyMatch(parameter -> "cookie".equals(parameter.getIn()))) {
                warnings.add(ImportMessage.warning(Code.UNSUPPORTED_FEATURE,
                        "Cookie parameters are documented but not sent by generated tests.", location));
            }
            String nonJsonBody = nonJsonOnlyRequestBody(openApi, operation.getRequestBody());
            if (nonJsonBody != null) {
                warnings.add(ImportMessage.warning(Code.UNSUPPORTED_FEATURE,
                        "The request body accepts only %s. Generated tests send JSON bodies, so this operation's body is documented but not generated."
                                .formatted(nonJsonBody),
                        location));
            }
            if (!warnings.isEmpty()) {
                operationWarnings.put(key, warnings);
                messages.addAll(warnings);
            }
        }));
        return messages;
    }

    /** Media types of a request body that offers no JSON variant, or {@code null}. */
    private static String nonJsonOnlyRequestBody(OpenAPI openApi, RequestBody requestBody) {
        RequestBody resolved = requestBody;
        if (resolved != null && resolved.get$ref() != null && openApi.getComponents() != null
                && openApi.getComponents().getRequestBodies() != null) {
            String ref = resolved.get$ref();
            resolved = openApi.getComponents().getRequestBodies().get(ref.substring(ref.lastIndexOf('/') + 1));
        }
        if (resolved == null) {
            return null;
        }
        Content content = resolved.getContent();
        if (content == null || content.isEmpty()) {
            return null;
        }
        boolean hasJson = content.keySet().stream().anyMatch(SpecificationAnalyzer::isJsonMediaType);
        return hasJson ? null : String.join(", ", content.keySet());
    }

    static boolean isJsonMediaType(String mediaType) {
        String type = mediaType.toLowerCase(Locale.ROOT).split(";", 2)[0].strip();
        return type.equals("application/json") || type.endsWith("+json") || type.equals("*/*");
    }

    private static SpecificationRejectedException rejected(List<ImportMessage> messages) {
        List<ImportMessage> ordered = new ArrayList<>(messages.stream().filter(ImportMessage::isError).toList());
        ordered.addAll(messages.stream().filter(message -> !message.isError()).toList());
        return new SpecificationRejectedException(limit(ordered));
    }

    private static List<ImportMessage> limit(List<ImportMessage> messages) {
        return messages.size() <= MAX_MESSAGES ? List.copyOf(messages) : List.copyOf(messages.subList(0, MAX_MESSAGES));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
