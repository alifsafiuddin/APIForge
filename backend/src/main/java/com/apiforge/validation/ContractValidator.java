package com.apiforge.validation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.apiforge.schema.GuardedPattern;
import com.apiforge.schema.MediaTypes;
import com.apiforge.schema.Pointers;
import com.apiforge.schema.Schema;
import com.apiforge.schema.SchemaResolver;
import com.apiforge.schema.SchemaValidator;
import com.apiforge.schema.SchemaValidator.Direction;
import com.apiforge.schema.SchemaValidator.Violation;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a response against the operation's documented responses (the stored, normalized
 * contract of one specification version):
 *
 * <ol>
 * <li>STATUS_DOCUMENTED — the status has a documented response (exact code, then its class such as
 * {@code 4XX}, then {@code default}); an undocumented status fails.</li>
 * <li>CONTENT_TYPE — the Content-Type matches a documented media type (ranges such as
 * {@code application/*} included).</li>
 * <li>JSON_SYNTAX — a JSON body parses.</li>
 * <li>BODY_SCHEMA — the body satisfies the documented schema: one FAIL assertion per violation, with
 * its JSON path, expected and actual value.</li>
 * </ol>
 *
 * <p>Checks that do not apply are SKIPPED with the reason (no documented body, no schema, no
 * matching operation); checks that apply but cannot be decided are INCONCLUSIVE (truncated body,
 * unsupported media type or schema keyword). Neither is ever reported as PASS.
 */
public final class ContractValidator {

    /** The documented responses of one operation, as stored at import. */
    public record DocumentedOperation(String method, String path, List<Map<String, Object>> responses) {

        public String operationKey() {
            return method + " " + path;
        }
    }

    /**
     * @param body the body as text, or {@code null} when empty or binary
     */
    public record ActualResponse(int status, String contentType, String body, long sizeBytes, boolean truncated) {

        boolean hasBody() {
            return sizeBytes > 0 || (body != null && !body.isEmpty());
        }
    }

    public static final String STATUS_DOCUMENTED = "STATUS_DOCUMENTED";
    public static final String CONTENT_TYPE = "CONTENT_TYPE";
    public static final String JSON_SYNTAX = "JSON_SYNTAX";
    public static final String BODY_SCHEMA = "BODY_SCHEMA";
    static final int MAX_BODY_FINDINGS = 50;

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .build();

    private final SchemaResolver resolver;
    private final SchemaValidator validator = new SchemaValidator(new GuardedPattern());

    public ContractValidator(Map<?, ?> componentSchemas) {
        this.resolver = new SchemaResolver(componentSchemas);
    }

    /**
     * @param operation    the documented operation, or {@code null} when none matches the request
     * @param validateBody whether the body is checked against its schema (a test can turn this off)
     */
    public List<Assertion> validate(DocumentedOperation operation, ActualResponse response, boolean validateBody) {
        List<Assertion> assertions = new ArrayList<>();
        // A body that declares JSON must be JSON, whatever the contract says.
        Parsed parsed = parseIfJson(response);
        if (operation == null) {
            assertions.add(Assertion.skipped(STATUS_DOCUMENTED,
                    "No operation of the specification matches this request; the response was not validated against the contract."));
            addIfPresent(assertions, parsed.syntax());
            return assertions;
        }
        String status = String.valueOf(response.status());
        Match match = documentedResponse(operation.responses(), status);
        if (match == null) {
            List<String> documented = operation.responses().stream().map(r -> String.valueOf(r.get("status"))).toList();
            assertions.add(Assertion.fail(STATUS_DOCUMENTED, "Status %s is not documented for %s.".formatted(status,
                    operation.operationKey()), documented.isEmpty() ? "a documented status" : "one of " + String.join(", ", documented),
                    status));
            addIfPresent(assertions, parsed.syntax());
            assertions.add(Assertion.skipped(BODY_SCHEMA, "Not validated: there is no documented response for status " + status + "."));
            return assertions;
        }
        assertions.add(Assertion.pass(STATUS_DOCUMENTED, match.key().equals(status)
                ? "Status " + status + " is documented."
                : "Status " + status + " is covered by the documented '" + match.key() + "' response."));

        Map<?, ?> content = match.response().get("content") instanceof Map<?, ?> map ? map : Map.of();
        if (content.isEmpty()) {
            String reason = "The documented " + match.key() + " response has no body";
            if (response.hasBody()) {
                assertions.add(Assertion.skipped(CONTENT_TYPE, reason + "; the content type was not checked."));
                addIfPresent(assertions, parsed.syntax());
                assertions.add(Assertion.skipped(BODY_SCHEMA, reason + " or schema; the received body ("
                        + describeBody(response) + ") was not validated."));
            } else {
                assertions.add(Assertion.skipped(BODY_SCHEMA, reason + "; none was received."));
            }
            return assertions;
        }
        List<String> mediaTypes = content.keySet().stream().map(String::valueOf).toList();
        if (!response.hasBody()) {
            assertions.add(Assertion.fail(BODY_SCHEMA, "The documented %s response has a body (%s), but the response is empty."
                    .formatted(match.key(), String.join(", ", mediaTypes)), "a " + String.join(" or ", mediaTypes) + " body",
                    "empty body"));
            return assertions;
        }
        String mediaKey = matchingMediaType(mediaTypes, response.contentType());
        if (response.contentType() == null || mediaKey == null) {
            assertions.add(Assertion.fail(CONTENT_TYPE, response.contentType() == null
                    ? "The response has a body but no Content-Type header."
                    : "Content-Type '%s' is not documented for %s.".formatted(response.contentType(), match.key()),
                    String.join(" or ", mediaTypes), response.contentType() == null ? "no Content-Type" : response.contentType()));
            addIfPresent(assertions, parsed.syntax());
            assertions.add(Assertion.skipped(BODY_SCHEMA, "Not validated: the content type does not match a documented media type."));
            return assertions;
        }
        assertions.add(Assertion.pass(CONTENT_TYPE, "Content-Type '%s' matches the documented '%s'."
                .formatted(response.contentType(), mediaKey)));
        addIfPresent(assertions, parsed.syntax());

        Object schemaNode = content.get(mediaKey) instanceof Map<?, ?> media ? media.get("schema") : null;
        if (!validateBody) {
            assertions.add(Assertion.skipped(BODY_SCHEMA, "Schema validation is turned off for this test."));
            return assertions;
        }
        if (schemaNode == null) {
            assertions.add(Assertion.skipped(BODY_SCHEMA, "The documented '%s' media type of the %s response has no schema."
                    .formatted(mediaKey, match.key())));
            return assertions;
        }
        if (parsed.syntax() == null) {
            assertions.add(Assertion.inconclusive(BODY_SCHEMA, "A schema is documented for '%s', but only JSON bodies are validated."
                    .formatted(mediaKey)));
            return assertions;
        }
        if (parsed.syntax().result() == Assertion.Result.INCONCLUSIVE) {
            assertions.add(Assertion.inconclusive(BODY_SCHEMA, "The body was truncated, so it could not be validated."));
            return assertions;
        }
        if (parsed.syntax().result() == Assertion.Result.FAIL) {
            assertions.add(Assertion.skipped(BODY_SCHEMA, "Not validated: the body is not valid JSON."));
            return assertions;
        }
        Schema schema = resolver.resolve(schemaNode, "#/paths/" + Pointers.escape(operation.path()) + "/"
                + operation.method().toLowerCase(Locale.ROOT) + "/responses/" + Pointers.escape(match.key())
                + "/content/" + Pointers.escape(mediaKey) + "/schema");
        SchemaValidator.Result result = validator.validate(parsed.value(), schema, Direction.RESPONSE);
        String against = schema.name() != null ? "schema " + schema.name() : "the documented schema";
        List<Violation> violations = result.violations();
        for (int index = 0; index < violations.size() && index < MAX_BODY_FINDINGS; index++) {
            Violation violation = violations.get(index);
            assertions.add(new Assertion(BODY_SCHEMA, Assertion.Result.FAIL, violation.jsonPath() + " "
                    + violation.message() + ".", violation.jsonPath(), violation.keyword(), violation.expected(),
                    violation.actual()));
        }
        if (violations.size() > MAX_BODY_FINDINGS) {
            assertions.add(Assertion.fail(BODY_SCHEMA, "%d further violations were not listed."
                    .formatted(violations.size() - MAX_BODY_FINDINGS), null, null));
        }
        if (!result.unevaluated().isEmpty()) {
            assertions.add(Assertion.inconclusive(BODY_SCHEMA, "Could not evaluate: " + String.join(", ", result.unevaluated())
                    + "."));
        }
        if (violations.isEmpty() && result.unevaluated().isEmpty()) {
            assertions.add(Assertion.pass(BODY_SCHEMA, "The body matches " + against + "."));
        }
        return assertions;
    }

    /** The JSON syntax check ({@code null} when the body does not declare JSON) and the parsed value. */
    private record Parsed(Assertion syntax, Object value) {
    }

    private static Parsed parseIfJson(ActualResponse response) {
        if (!response.hasBody() || response.contentType() == null || !MediaTypes.isJson(response.contentType())) {
            return new Parsed(null, null);
        }
        if (response.truncated()) {
            return new Parsed(Assertion.inconclusive(JSON_SYNTAX,
                    "The body exceeded the size limit and was truncated, so it was not parsed."), null);
        }
        try {
            Object value = JSON.readValue(response.body() == null ? "" : response.body(), Object.class);
            return new Parsed(Assertion.pass(JSON_SYNTAX, "The body is well-formed JSON."), value);
        } catch (JacksonException ex) {
            String where = ex.getLocation() == null ? "" : " at line " + ex.getLocation().getLineNr() + ", column "
                    + ex.getLocation().getColumnNr();
            return new Parsed(Assertion.fail(JSON_SYNTAX, "The body is not valid JSON" + where + ".", "valid JSON",
                    "malformed JSON"), null);
        }
    }

    private static void addIfPresent(List<Assertion> assertions, Assertion assertion) {
        if (assertion != null) {
            assertions.add(assertion);
        }
    }

    /** The documented response for a status and the key that matched it. */
    record Match(String key, Map<?, ?> response) {
    }

    /** Exact code first, then the status class ({@code 4XX}), then {@code default}. */
    static Match documentedResponse(List<Map<String, Object>> responses, String status) {
        String statusClass = status.charAt(0) + "XX";
        for (String candidate : List.of(status, statusClass, "DEFAULT")) {
            for (Map<String, Object> response : responses) {
                String key = String.valueOf(response.get("status"));
                if (key.toUpperCase(Locale.ROOT).equals(candidate)) {
                    return new Match(key, response);
                }
            }
        }
        return null;
    }

    /** The documented media type covering {@code contentType}: an exact match wins over ranges. */
    static String matchingMediaType(List<String> documented, String contentType) {
        if (contentType == null) {
            return null;
        }
        String essence = MediaTypes.essence(contentType);
        for (String candidate : documented) {
            if (MediaTypes.essence(candidate).equals(essence)) {
                return candidate;
            }
        }
        for (String candidate : documented) {
            if (MediaTypes.covers(candidate, contentType)) {
                return candidate;
            }
        }
        return null;
    }

    private static String describeBody(ActualResponse response) {
        return (response.contentType() == null ? "no content type" : response.contentType()) + ", "
                + response.sizeBytes() + " bytes";
    }

    /**
     * The operation a concrete request belongs to. Templates match one segment per parameter;
     * among several matches the one with the most literal segments wins
     * ({@code /pets/mine} before {@code /pets/{id}}).
     */
    public static Optional<DocumentedOperation> match(String method, String path, Collection<DocumentedOperation> operations) {
        DocumentedOperation best = null;
        int bestLiterals = -1;
        for (DocumentedOperation operation : operations) {
            if (!operation.method().equalsIgnoreCase(method)) {
                continue;
            }
            int literals = literalSegments(operation.path(), path);
            if (literals > bestLiterals) {
                best = operation;
                bestLiterals = literals;
            }
        }
        return Optional.ofNullable(best);
    }

    private static final Pattern TEMPLATE = Pattern.compile("^\\{[^}/]+}$");

    /** Number of literal segments when {@code template} matches {@code path}, else -1. */
    private static int literalSegments(String template, String path) {
        String[] expected = template.split("/", -1);
        String[] actual = path.split("/", -1);
        if (expected.length != actual.length) {
            return -1;
        }
        int literals = 0;
        for (int index = 0; index < expected.length; index++) {
            if (TEMPLATE.matcher(expected[index]).matches()) {
                if (actual[index].isEmpty()) {
                    return -1;
                }
            } else if (expected[index].equals(actual[index])) {
                literals++;
            } else {
                return -1;
            }
        }
        return literals;
    }

    /** Keyed by operation key, for runs. */
    public static Map<String, DocumentedOperation> index(Collection<DocumentedOperation> operations) {
        Map<String, DocumentedOperation> byKey = new LinkedHashMap<>();
        operations.forEach(operation -> byKey.put(operation.operationKey(), operation));
        return byKey;
    }
}
