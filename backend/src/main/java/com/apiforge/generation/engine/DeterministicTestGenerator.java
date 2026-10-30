package com.apiforge.generation.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.apiforge.schema.GuardedPattern;
import com.apiforge.schema.MediaTypes;
import com.apiforge.schema.Pointers;
import com.apiforge.schema.Schema;
import com.apiforge.schema.SchemaResolver;
import com.apiforge.schema.SchemaValidator;
import com.apiforge.common.json.JsonValues;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.RequestTemplate;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.Body;
import com.apiforge.testcase.domain.TestCase.Category;
import com.apiforge.testcase.domain.TestCase.Definition;
import com.apiforge.testcase.domain.TestCase.RiskLevel;

import tools.jackson.databind.json.JsonMapper;

/**
 * Derives test scenarios from one operation of an imported specification, without AI and without
 * randomness: the same operation always yields the same tests, in the same order, with the same
 * identifiers.
 *
 * <p>Every scenario starts from a request that the {@link SchemaValidator} confirmed to be valid,
 * and every negative scenario changes exactly one thing. A mutation is only kept when validation
 * shows that the changed value breaks the targeted constraint and nothing else; otherwise the
 * scenario is reported as skipped, with the reason, instead of producing a misleading test.
 */
public final class DeterministicTestGenerator {

    /** Stored with every generated test; bump when the generated content changes. */
    public static final String VERSION = "2";
    public static final int MAX_TESTS_PER_OPERATION = 100;
    /** Longest string a test may send (SECURITY.md §6.5: no oversized payloads). */
    static final int MAX_STRING_LENGTH = 10_000;
    /** How deep nested body objects are explored for field scenarios. */
    static final int MAX_BODY_DEPTH = 3;
    private static final int MAX_SCENARIO_KEY = 300;
    private static final Set<String> IGNORED_HEADERS = Set.of("accept", "content-type", "authorization");
    private static final List<String> FOREIGN_CONTENT_TYPES = List.of("text/plain", "application/xml",
            "application/octet-stream");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The version-wide context: component schemas and security schemes. */
    public record Specification(Map<String, Object> componentSchemas, Map<String, Object> securitySchemes) {
    }

    /** One normalized operation as stored at import. */
    public record Operation(String method, String path, List<Map<String, Object>> parameters,
            Map<String, Object> requestBody, List<Map<String, Object>> responses, List<Map<String, Object>> security) {

        public String operationKey() {
            return method + " " + path;
        }
    }

    /** A scenario that was considered but deliberately not generated. */
    public record Skipped(String operationKey, String scenario, String reason) {
    }

    public record Output(List<Definition> tests, List<Skipped> skipped) {
    }

    public Output generate(Specification specification, Operation operation) {
        return new Run(specification, operation).execute();
    }

    private record Param(String name, String in, boolean required, Schema schema, Object minimalValue,
            Object completeValue, String failure) {

        String label() {
            return in + " parameter '" + name + "'";
        }
    }

    private record BodyPlan(boolean present, boolean required, List<String> mediaTypes, String contentType,
            Schema schema, Object minimal, Object complete, String failure) {

        static BodyPlan absent() {
            return new BodyPlan(false, false, List.of(), null, null, null, null, null);
        }
    }

    /** A value to mutate: a body location (JSON pointer) or a parameter. */
    private record Target(String scope, String id, Schema schema, Object base, String label, String location) {

        boolean body() {
            return "body".equals(scope);
        }

        /** Pointer of the value inside what is validated (the body, or the parameter value itself). */
        String pointer() {
            return body() ? id : "";
        }
    }

    private static final class Run {

        private final Specification specification;
        private final Operation operation;
        private final String operationKey;
        private final String operationPointer;
        private final RiskLevel risk;
        private final SchemaResolver resolver;
        private final SchemaValidator validator;
        private final ValueSynthesizer synthesizer;
        private final ResponseCatalog responses;
        private final List<Definition> tests = new ArrayList<>();
        private final List<Skipped> skipped = new ArrayList<>();
        private final Set<String> scenarioKeys = new HashSet<>();
        private int overflow;
        private final List<Param> params = new ArrayList<>();
        private BodyPlan body = BodyPlan.absent();
        private RequestTemplate minimal;
        private RequestTemplate complete;

        Run(Specification specification, Operation operation) {
            this.specification = specification;
            this.operation = operation;
            this.operationKey = operation.operationKey();
            this.operationPointer = "#/paths/" + Pointers.escape(operation.path()) + "/"
                    + operation.method().toLowerCase(Locale.ROOT);
            this.risk = RiskLevel.forMethod(operation.method());
            this.resolver = new SchemaResolver(specification.componentSchemas());
            GuardedPattern patterns = new GuardedPattern();
            this.validator = new SchemaValidator(patterns);
            this.synthesizer = new ValueSynthesizer(validator, patterns);
            this.responses = new ResponseCatalog(operation.responses());
        }

        Output execute() {
            String paramFailure = collectParameters();
            if (paramFailure != null) {
                skip("All scenarios", paramFailure);
                return output();
            }
            body = planBody();
            minimal = template(false);
            complete = template(true);
            if (body.required() && body.failure() != null) {
                missingBody();
                skip("All other scenarios", "a valid request body cannot be built: " + body.failure());
                return output();
            }
            happyPath();
            authentication();
            parameterScenarios();
            bodyScenarios();
            return output();
        }

        private Output output() {
            if (overflow > 0) {
                skipped.add(new Skipped(operationKey, overflow + " further scenario" + (overflow == 1 ? "" : "s"),
                        "the limit of " + MAX_TESTS_PER_OPERATION + " tests per operation was reached"));
            }
            return new Output(List.copyOf(tests), List.copyOf(skipped));
        }

        // --- Building the valid request ---

        /** @return why no valid request can be built, or {@code null} */
        private String collectParameters() {
            for (Map<String, Object> parameter : operation.parameters()) {
                String name = String.valueOf(parameter.get("name"));
                String in = String.valueOf(parameter.get("in")).toLowerCase(Locale.ROOT);
                boolean required = "path".equals(in) || Boolean.TRUE.equals(parameter.get("required"));
                String label = in + " parameter '" + name + "'";
                if ("cookie".equals(in)) {
                    skip("Scenarios for " + label, "cookie parameters are not generated (APIForge requests do not send cookies)");
                    continue;
                }
                if ("header".equals(in) && IGNORED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    skip("Scenarios for " + label, "OpenAPI ignores header parameters named Accept, Content-Type and "
                            + "Authorization; these headers come from the body and the environment");
                    continue;
                }
                Param param = describeParameter(parameter, name, in, required, label);
                if (param.failure() != null && required) {
                    return "the required " + label + " has no valid value: " + param.failure();
                }
                params.add(param);
            }
            return null;
        }

        private Param describeParameter(Map<String, Object> parameter, String name, String in, boolean required,
                String label) {
            if (parameter.get("schema") == null) {
                return new Param(name, in, required, null, null, null,
                        "it is described with 'content' instead of 'schema', which the generator does not support");
            }
            Schema schema = resolver.resolve(parameter.get("schema"), label);
            if ("object".equals(schema.primaryType())) {
                return new Param(name, in, required, schema, null, null, "object-valued parameters are not generated");
            }
            List<Object> documented = new ArrayList<>();
            if (parameter.containsKey("example")) {
                documented.add(parameter.get("example"));
            }
            if (parameter.get("examples") instanceof Map<?, ?> examples) {
                examples.values().forEach(example -> {
                    if (example instanceof Map<?, ?> map && map.containsKey("value")) {
                        documented.add(map.get("value"));
                    }
                });
            }
            for (Object example : documented) {
                if (validator.validate(example, schema).valid()) {
                    Object value = JsonValues.deepCopy(example);
                    return new Param(name, in, required, schema, value, value, null);
                }
            }
            // Parameters have one value; for arrays the "complete" form has at least one item, because an
            // empty array parameter cannot be told apart from an absent one.
            ValueSynthesizer.Result value = synthesizer.valid(schema, true, label);
            if (!value.ok()) {
                value = synthesizer.valid(schema, false, label);
            }
            if (!value.ok()) {
                return new Param(name, in, required, schema, null, null, value.failure());
            }
            if (value.value() instanceof List<?> list && list.isEmpty()) {
                return new Param(name, in, required, schema, null, null,
                        "its schema only allows an empty array, which cannot be sent as a parameter");
            }
            return new Param(name, in, required, schema, value.value(), value.value(), null);
        }

        private BodyPlan planBody() {
            Map<String, Object> requestBody = operation.requestBody();
            if (requestBody == null) {
                return BodyPlan.absent();
            }
            boolean required = Boolean.TRUE.equals(requestBody.get("required"));
            Map<String, Object> content = JsonValues.copyObject(requestBody.get("content"));
            List<String> mediaTypes = List.copyOf(content.keySet());
            String key = mediaTypes.stream().filter(type -> MediaTypes.essence(type).equals("application/json")).findFirst()
                    .or(() -> mediaTypes.stream().filter(MediaTypes::isJson).findFirst())
                    .or(() -> mediaTypes.stream().filter(type -> MediaTypes.covers(type, "application/json")).findFirst())
                    .orElse(null);
            if (key == null) {
                String documented = mediaTypes.isEmpty() ? "no media type" : String.join(", ", mediaTypes);
                return new BodyPlan(true, required, mediaTypes, null, null, null, null,
                        "the body is documented as " + documented + "; the generator builds JSON bodies only");
            }
            String contentType = MediaTypes.isJson(key) ? key : "application/json";
            Map<String, Object> media = JsonValues.copyObject(content.get(key));
            Schema schema = resolver.resolve(media.get("schema"),
                    operationPointer + "/requestBody/content/" + Pointers.escape(key) + "/schema");

            ValueSynthesizer.Result minimalBody = synthesizer.valid(schema, false, "body");
            Object minimalValue = minimalBody.ok() ? minimalBody.value() : documentedBody(media, schema);
            if (!minimalBody.ok() && minimalValue == null) {
                return new BodyPlan(true, required, mediaTypes, contentType, schema, null, null, minimalBody.failure());
            }
            ValueSynthesizer.Result completeBody = synthesizer.valid(schema, true, "body");
            return new BodyPlan(true, required, mediaTypes, contentType, schema, minimalValue,
                    completeBody.ok() ? completeBody.value() : minimalValue, null);
        }

        /** A media-level example of the whole body that satisfies the schema, or {@code null}. */
        private Object documentedBody(Map<String, Object> media, Schema schema) {
            List<Object> documented = new ArrayList<>();
            if (media.containsKey("example")) {
                documented.add(media.get("example"));
            }
            if (media.get("examples") instanceof Map<?, ?> examples) {
                examples.values().forEach(example -> {
                    if (example instanceof Map<?, ?> map && map.containsKey("value")) {
                        documented.add(map.get("value"));
                    }
                });
            }
            for (Object example : documented) {
                if (validator.validate(example, schema).valid()) {
                    return JsonValues.deepCopy(example);
                }
            }
            return null;
        }

        private RequestTemplate template(boolean withOptional) {
            LinkedHashMap<String, Object> path = new LinkedHashMap<>();
            LinkedHashMap<String, Object> query = new LinkedHashMap<>();
            LinkedHashMap<String, String> headers = new LinkedHashMap<>();
            for (Param param : params) {
                if (param.failure() != null || (!param.required() && !withOptional)) {
                    continue;
                }
                Object value = withOptional ? param.completeValue() : param.minimalValue();
                switch (param.in()) {
                    case "path" -> path.put(param.name(), value);
                    case "query" -> query.put(param.name(), value);
                    case "header" -> headers.put(param.name(), headerValue(value));
                    default -> {
                        // cookie parameters were skipped when collected
                    }
                }
            }
            Body requestBody = Body.none();
            if (body.present() && body.failure() == null && (body.required() || withOptional)) {
                requestBody = Body.json(body.contentType(), withOptional ? body.complete() : body.minimal());
            }
            return new RequestTemplate(path, query, headers, requestBody, AuthMode.ENVIRONMENT);
        }

        // --- Scenarios ---

        private void happyPath() {
            boolean single = JsonValues.jsonEquals(minimal.toMap(), complete.toMap());
            String notes = verificationNotes();
            add("happy.minimal", single ? "Valid request" : "Valid request (required fields only)", Category.HAPPY_PATH,
                    minimal, responses.success(), describeMinimal(single) + notes, operationPointer);
            if (!single) {
                add("happy.complete", "Valid request (all documented fields)", Category.HAPPY_PATH, complete,
                        responses.success(), "Sends every documented parameter and body property, required and optional "
                                + "(nested objects down to " + ValueSynthesizer.COMPLETE_DEPTH + " levels), each with a "
                                + "value that satisfies its schema, so all documented fields are exercised together."
                                + notes, operationPointer);
            }
        }

        private String describeMinimal(boolean single) {
            List<String> parts = new ArrayList<>();
            for (String in : List.of("path", "query", "header")) {
                List<String> names = params.stream()
                        .filter(param -> param.in().equals(in) && param.required() && param.failure() == null)
                        .map(param -> "'" + param.name() + "'").toList();
                if (!names.isEmpty()) {
                    parts.add((in.equals("path") ? "" : "required ") + in + " parameter" + (names.size() == 1 ? " " : "s ")
                            + String.join(", ", names));
                }
            }
            if (body.present() && body.required()) {
                List<String> required = body.schema().required().stream()
                        .filter(property -> !body.schema().property(property).readOnly()).toList();
                parts.add(required.isEmpty() || !(body.minimal() instanceof Map<?, ?>) ? "a request body"
                        : "a request body with the required properties " + String.join(", ", required));
            } else if (body.present()) {
                parts.add("no request body (it is optional)");
            }
            String sent = parts.isEmpty() ? "The operation has no required parameters or body, so none are sent."
                    : (single ? "Sends " : "Sends only what the contract requires: ") + String.join("; ", parts) + ".";
            return sent + " Values come from documented examples, defaults and enums where they satisfy the schema, "
                    + "otherwise they are synthesized from types and constraints; every value was validated against its "
                    + "schema.";
        }

        /** Mentions schema keywords the validator could not evaluate for the valid request. */
        private String verificationNotes() {
            Set<String> unevaluated = new TreeSet<>();
            if (body.present() && body.failure() == null) {
                unevaluated.addAll(validator.validate(body.complete(), body.schema()).unevaluated());
            }
            for (Param param : params) {
                if (param.failure() == null && param.schema() != null) {
                    unevaluated.addAll(validator.validate(param.completeValue(), param.schema()).unevaluated());
                }
            }
            return unevaluated.isEmpty() ? ""
                    : " Not verified by the generator: " + String.join(", ", unevaluated) + ".";
        }

        private void authentication() {
            List<Map<String, Object>> security = operation.security();
            if (security.isEmpty()) {
                skip("Authentication scenarios", "the operation declares no security requirement");
                return;
            }
            if (security.stream().anyMatch(Map::isEmpty)) {
                skip("Authentication scenarios", "authentication is optional: the security requirements include an "
                        + "empty requirement ({})");
                return;
            }
            List<String> alternatives = security.stream().map(this::describeRequirement).toList();
            String required = "The operation requires " + String.join(" or ", alternatives) + ".";
            add("auth.none", "Request without credentials", Category.AUTH, minimal.withAuth(AuthMode.NONE),
                    responses.authenticationFailure(), required + " The otherwise valid request is sent without any "
                            + "credentials.", operationPointer + "/security");
            if (security.stream().anyMatch(requirement -> requirement.keySet().stream().allMatch(this::imitable))) {
                add("auth.invalid", "Request with invalid credentials", Category.AUTH, minimal.withAuth(AuthMode.INVALID),
                        responses.authenticationFailure(), required + " The otherwise valid request carries credentials "
                                + "of the right form but with a wrong value (an unknown token, key or password).",
                        operationPointer + "/security");
            } else {
                skip("Request with invalid credentials", "the security schemes (" + String.join(" or ", alternatives)
                        + ") cannot be imitated with a request header or query parameter");
            }
        }

        private String describeRequirement(Map<String, Object> requirement) {
            List<String> schemes = new ArrayList<>();
            for (String name : requirement.keySet()) {
                Object scheme = specification.securitySchemes() == null ? null : specification.securitySchemes().get(name);
                String type = scheme instanceof Map<?, ?> map ? String.valueOf(map.get("type")) : "undefined";
                schemes.add(name + " (" + type + ")");
            }
            return String.join(" + ", schemes);
        }

        /** Whether wrong credentials for this scheme can be sent: HTTP basic/bearer, API key, OAuth 2, OIDC. */
        private boolean imitable(String schemeName) {
            if (specification.securitySchemes() == null
                    || !(specification.securitySchemes().get(schemeName) instanceof Map<?, ?> scheme)) {
                return false;
            }
            String type = String.valueOf(scheme.get("type"));
            return switch (type) {
                case "http" -> List.of("basic", "bearer").contains(String.valueOf(scheme.get("scheme")).toLowerCase(Locale.ROOT));
                case "apiKey" -> List.of("header", "query").contains(String.valueOf(scheme.get("in")));
                case "oauth2", "openIdConnect" -> true;
                default -> false;
            };
        }

        private void parameterScenarios() {
            for (Param param : params) {
                if (param.failure() != null) {
                    skip("Scenarios for " + param.label(), param.failure());
                    continue;
                }
                if ("path".equals(param.in())) {
                    skip("Missing " + param.label(), "not representable: omitting a path parameter changes the URL to a "
                            + "different route");
                } else if (param.required()) {
                    RequestTemplate without = "query".equals(param.in()) ? complete.withQuery(param.name(), null)
                            : complete.withHeader(param.name(), null);
                    add(param.in() + ".missing:" + param.name(), "Missing required " + param.label(),
                            "query".equals(param.in()) ? Category.QUERY_PARAMETER : Category.HEADER, without,
                            responses.rejection(List.of("400", "422")), "'" + param.name() + "' is a required "
                                    + param.in() + " parameter. The request omits it.", param.label());
                }
                valueScenarios(new Target(param.in(), param.name(), param.schema(), param.completeValue(), param.label(),
                        param.label()));
            }
        }

        private void missingBody() {
            add("body.missing", "Missing request body", Category.MISSING_FIELD, complete.withBody(Body.none()),
                    responses.rejection(List.of("400", "415", "422")), "The request body is required "
                            + "(requestBody.required: true). The request is sent without a body.",
                    operationPointer + "/requestBody");
        }

        private void bodyScenarios() {
            if (!body.present()) {
                return;
            }
            if (body.failure() != null) {
                skip("Request body scenarios", body.failure());
                return;
            }
            if (body.required()) {
                missingBody();
            }
            contentType();
            Target root = new Target("body", "", body.schema(), body.complete(), "request body", body.schema().location());
            valueScenarios(root);
            explore(body.schema(), body.complete(), "", 0);
        }

        private void contentType() {
            List<String> documented = body.mediaTypes();
            if (documented.stream().anyMatch(range -> MediaTypes.essence(range).equals("*/*"))) {
                skip("Unsupported content type", "the operation accepts any media type (*/*)");
                return;
            }
            String foreign = FOREIGN_CONTENT_TYPES.stream().filter(type -> !MediaTypes.anyCovers(documented, type))
                    .findFirst().orElse(null);
            if (foreign == null) {
                skip("Unsupported content type", "the operation accepts text, XML and binary bodies");
                return;
            }
            add("content-type.unsupported", "Unsupported content type " + foreign, Category.CONTENT_TYPE,
                    complete.withBody(Body.raw(foreign, JSON.writeValueAsString(body.complete()))),
                    responses.rejection(List.of("415")), "The operation documents the request media type"
                            + (documented.size() == 1 ? " " : "s ") + String.join(", ", documented) + ". The valid "
                            + "body is sent with Content-Type " + foreign + ", which is not among them.",
                    operationPointer + "/requestBody/content");
        }

        /** Field scenarios for the properties of an object (and the first item of arrays), recursively. */
        private void explore(Schema schema, Object value, String pointer, int depth) {
            if (depth >= MAX_BODY_DEPTH) {
                return;
            }
            if (value instanceof List<?> list && !list.isEmpty()) {
                Schema items = schema.items();
                String itemPointer = pointer + "/0";
                valueScenarios(new Target("body", itemPointer, items, list.getFirst(), fieldLabel(itemPointer),
                        items.location()));
                explore(items, list.getFirst(), itemPointer, depth + 1);
                return;
            }
            if (!(value instanceof Map<?, ?> map)) {
                return;
            }
            if (!schema.oneOf().isEmpty() || !schema.anyOf().isEmpty()) {
                skip("Field scenarios inside " + (pointer.isEmpty() ? "the request body" : fieldLabel(pointer)),
                        "it is a oneOf/anyOf composition: a changed value may match a different alternative, so no "
                                + "single expected outcome can be derived");
                return;
            }
            Set<String> required = new LinkedHashSet<>(schema.required());
            Set<String> names = new TreeSet<>(required);
            map.keySet().forEach(key -> names.add(String.valueOf(key)));
            for (String name : names) {
                Schema property = schema.property(name);
                if (property.readOnly() || (!schema.declaresProperty(name) && !required.contains(name))) {
                    continue;
                }
                String childPointer = Pointers.child(pointer, name);
                if (required.contains(name)) {
                    missingField(schema, name, childPointer);
                }
                if (!map.containsKey(name)) {
                    continue;
                }
                Object child = map.get(name);
                valueScenarios(new Target("body", childPointer, property, child, fieldLabel(childPointer),
                        property.location()));
                explore(property, child, childPointer, depth + 1);
            }
        }

        private void missingField(Schema parent, String name, String pointer) {
            String label = fieldLabel(pointer);
            String scenario = "Missing required " + label;
            Object mutated = Pointers.without(body.complete(), pointer);
            SchemaValidator.Result result = validator.validate(mutated, body.schema());
            if (!result.onlyViolates(pointer, Set.of("required"))) {
                skip(scenario, otherFault(result, pointer, Set.of("required")));
                return;
            }
            add("body.missing:" + pointer, scenario, Category.MISSING_FIELD,
                    complete.withBody(Body.json(body.contentType(), mutated)), responses.rejection(List.of("400", "422")),
                    "'" + name + "' is listed in 'required' of " + parent.describe() + ". The request omits it.",
                    parent.location() + "/required");
        }

        // --- Value mutations shared by parameters and body fields ---

        private void valueScenarios(Target target) {
            Schema schema = target.schema();
            Set<String> types = new LinkedHashSet<>(schema.declaredTypes());
            types.remove("null");
            typeScenario(target, types);
            List<?> allowed = schema.enumValues();
            if (allowed != null && !allowed.isEmpty()) {
                enumScenario(target, allowed);
                return; // other constraints are moot when only listed values are allowed
            }
            if (target.base() instanceof Number && (schema.lower() != null || schema.upper() != null)) {
                boundScenarios(target, types);
            }
            if (target.base() instanceof String text && (schema.has("minLength") || schema.has("maxLength"))) {
                lengthScenarios(target, text);
            }
            if (target.base() instanceof List<?> list && (schema.has("minItems") || schema.has("maxItems"))) {
                itemScenarios(target, list);
            }
        }

        private void typeScenario(Target target, Set<String> types) {
            if (types.isEmpty()) {
                return;
            }
            String expected = String.join(" or ", types);
            Object wrong;
            if (!target.body()) {
                // Parameters travel as text: only non-string types can receive a value of the wrong type.
                if (types.size() != 1 || !Set.of("integer", "number", "boolean").contains(expected)) {
                    return;
                }
                wrong = "boolean".equals(expected) ? "not-a-boolean" : "not-a-number";
            } else if (!types.contains("string")) {
                wrong = switch (types.iterator().next()) {
                    case "object" -> target.id().isEmpty() ? List.of() : "not-an-object";
                    case "array" -> target.id().isEmpty() ? new LinkedHashMap<String, Object>() : "not-an-array";
                    case "boolean" -> "not-a-boolean";
                    default -> "not-a-number";
                };
            } else if (!types.contains("number") && !types.contains("integer")) {
                wrong = 12345L;
            } else if (!types.contains("boolean")) {
                wrong = Boolean.TRUE;
            } else {
                skip("Wrong type for " + target.label(), "the schema accepts strings, numbers and booleans");
                return;
            }
            String actual = JsonValues.typeName(wrong);
            String source = target.schema().name() != null ? "schema " + target.schema().name()
                    : target.schema().location();
            mutation(target, "type", "Wrong type for %s (%s instead of %s)".formatted(target.label(), actual, expected),
                    Category.INVALID_TYPE, List.of(wrong), Set.of("type"),
                    value -> "%s must be %s (%s); %s %s value (%s) is sent instead.".formatted(
                            capitalize(target.label()), expected, source, article(actual), actual, preview(value)));
        }

        private void enumScenario(Target target, List<?> allowed) {
            List<Object> candidates = new ArrayList<>();
            if (allowed.stream().allMatch(String.class::isInstance)) {
                for (String candidate : List.of("not-a-valid-value", "INVALID", "zz", "x")) {
                    if (!allowed.contains(candidate)) {
                        candidates.add(candidate);
                    }
                }
            } else if (allowed.stream().allMatch(Number.class::isInstance)) {
                BigDecimal max = allowed.stream().map(value -> JsonValues.decimal((Number) value))
                        .reduce(BigDecimal::max).orElseThrow();
                BigDecimal min = allowed.stream().map(value -> JsonValues.decimal((Number) value))
                        .reduce(BigDecimal::min).orElseThrow();
                candidates.add(ValueSynthesizer.normalize(max.add(BigDecimal.ONE)));
                candidates.add(ValueSynthesizer.normalize(min.subtract(BigDecimal.ONE)));
            } else if (allowed.size() == 1 && allowed.getFirst() instanceof Boolean only) {
                candidates.add(!only);
            }
            String name = "Invalid enum value for " + target.label();
            if (candidates.isEmpty()) {
                skip(name, "the enum mixes value types, so no single out-of-list value is meaningful");
                return;
            }
            String listed = allowed.stream().limit(10).map(DeterministicTestGenerator::preview)
                    .reduce((left, right) -> left + ", " + right).orElse("") + (allowed.size() > 10 ? ", …" : "");
            mutation(target, "enum", name, Category.ENUM_VALUE, candidates, Set.of("enum"),
                    value -> "%s only allows %s; %s is not one of them.".formatted(capitalize(target.label()), listed,
                            preview(value)));
        }

        private void boundScenarios(Target target, Set<String> types) {
            boolean integer = types.contains("integer") && !types.contains("number");
            Schema.Bound lower = target.schema().lower();
            Schema.Bound upper = target.schema().upper();
            BigDecimal step = integer ? BigDecimal.ONE
                    : target.schema().multipleOf() != null ? target.schema().multipleOf() : BigDecimal.ONE;
            String label = capitalize(target.label());
            if (lower != null) {
                String bound = lower.value().toPlainString();
                BigDecimal outside = lower.exclusive() ? lower.value() : lower.value().subtract(step);
                mutation(target, "below-minimum", lower.exclusive() ? "%s equal to exclusive minimum %s".formatted(label, bound)
                        : "%s below minimum %s".formatted(label, bound), Category.BOUNDARY_VALUE,
                        distinct(outside, lower.value().subtract(BigDecimal.ONE)), Set.of("minimum", "exclusiveMinimum"),
                        value -> lower.exclusive()
                                ? "%s has exclusiveMinimum %s, so %s itself is not allowed.".formatted(label, bound, preview(value))
                                : "%s has minimum %s; %s is just below it.".formatted(label, bound, preview(value)));
                BigDecimal inside = lower.exclusive()
                        ? (integer ? lower.value().setScale(0, RoundingMode.FLOOR).add(BigDecimal.ONE) : null)
                        : lower.value();
                if (inside != null) {
                    acceptance(target, "at-minimum", lower.exclusive()
                            ? "%s at the smallest value above exclusive minimum %s".formatted(label, bound)
                            : "%s at minimum %s".formatted(label, bound), Category.BOUNDARY_VALUE,
                            List.of(ValueSynthesizer.normalize(inside)),
                            value -> "Boundary check: %s is the smallest value %s allows, so the request must succeed."
                                    .formatted(preview(value), lower.exclusive() ? "exclusiveMinimum " + bound : "minimum " + bound));
                }
            }
            if (upper != null) {
                String bound = upper.value().toPlainString();
                BigDecimal outside = upper.exclusive() ? upper.value() : upper.value().add(step);
                mutation(target, "above-maximum", upper.exclusive() ? "%s equal to exclusive maximum %s".formatted(label, bound)
                        : "%s above maximum %s".formatted(label, bound), Category.BOUNDARY_VALUE,
                        distinct(outside, upper.value().add(BigDecimal.ONE)), Set.of("maximum", "exclusiveMaximum"),
                        value -> upper.exclusive()
                                ? "%s has exclusiveMaximum %s, so %s itself is not allowed.".formatted(label, bound, preview(value))
                                : "%s has maximum %s; %s is just above it.".formatted(label, bound, preview(value)));
                BigDecimal inside = upper.exclusive()
                        ? (integer ? upper.value().setScale(0, RoundingMode.CEILING).subtract(BigDecimal.ONE) : null)
                        : upper.value();
                if (inside != null) {
                    acceptance(target, "at-maximum", upper.exclusive()
                            ? "%s at the largest value below exclusive maximum %s".formatted(label, bound)
                            : "%s at maximum %s".formatted(label, bound), Category.BOUNDARY_VALUE,
                            List.of(ValueSynthesizer.normalize(inside)),
                            value -> "Boundary check: %s is the largest value %s allows, so the request must succeed."
                                    .formatted(preview(value), upper.exclusive() ? "exclusiveMaximum " + bound : "maximum " + bound));
                }
            }
        }

        private static List<Object> distinct(BigDecimal first, BigDecimal second) {
            List<Object> values = new ArrayList<>();
            values.add(ValueSynthesizer.normalize(first));
            if (first.compareTo(second) != 0) {
                values.add(ValueSynthesizer.normalize(second));
            }
            return values;
        }

        private void lengthScenarios(Target target, String base) {
            String label = capitalize(target.label());
            Integer minLength = target.schema().count("minLength");
            Integer maxLength = target.schema().count("maxLength");
            if (minLength != null && minLength > 0) {
                int shorter = minLength - 1;
                mutation(target, "too-short", "%s shorter than minLength %d".formatted(label, minLength),
                        Category.STRING_LENGTH, List.of(truncate(base, shorter), "x".repeat(shorter)), Set.of("minLength"),
                        value -> "%s has minLength %d; a %d-character string is sent.".formatted(label, minLength, shorter));
                acceptance(target, "at-min-length", "%s at minLength %d".formatted(label, minLength),
                        Category.STRING_LENGTH, List.of(truncate(base, minLength), "x".repeat(minLength)),
                        value -> "Boundary check: a string of exactly %d characters satisfies minLength %d, so the "
                                .formatted(minLength, minLength) + "request must succeed.");
            }
            if (maxLength != null) {
                if (maxLength >= MAX_STRING_LENGTH) {
                    skip("%s longer than maxLength %d".formatted(label, maxLength), "maxLength is at or above the "
                            + "generator's limit of %,d characters (no oversized requests)".formatted(MAX_STRING_LENGTH));
                    return;
                }
                int longer = maxLength + 1;
                mutation(target, "too-long", "%s longer than maxLength %d".formatted(label, maxLength),
                        Category.STRING_LENGTH, List.of(pad(base, longer), "x".repeat(longer)), Set.of("maxLength"),
                        value -> "%s has maxLength %d; a %d-character string is sent.".formatted(label, maxLength, longer));
                acceptance(target, "at-max-length", "%s at maxLength %d".formatted(label, maxLength),
                        Category.STRING_LENGTH, List.of(pad(base, maxLength), "x".repeat(maxLength)),
                        value -> "Boundary check: a string of exactly %d characters satisfies maxLength %d, so the "
                                .formatted(maxLength, maxLength) + "request must succeed.");
            }
        }

        private void itemScenarios(Target target, List<?> base) {
            String label = capitalize(target.label());
            Integer minItems = target.schema().count("minItems");
            Integer maxItems = target.schema().count("maxItems");
            if (minItems != null && minItems > 0) {
                int fewer = minItems - 1;
                if (fewer == 0 && !target.body()) {
                    skip("%s with fewer than %d items".formatted(label, minItems), "an empty array parameter cannot be "
                            + "told apart from an absent one");
                } else {
                    mutation(target, "too-few-items", "%s with fewer than minItems %d".formatted(label, minItems),
                            Category.ARRAY_LENGTH, arraysOfSize(target, base, fewer), Set.of("minItems"),
                            value -> "%s has minItems %d; an array of %d item%s is sent.".formatted(label, minItems, fewer,
                                    fewer == 1 ? "" : "s"));
                }
                acceptance(target, "at-min-items", "%s with exactly minItems %d".formatted(label, minItems),
                        Category.ARRAY_LENGTH, arraysOfSize(target, base, minItems),
                        value -> "Boundary check: %d item%s satisfy minItems %d, so the request must succeed."
                                .formatted(minItems, minItems == 1 ? "" : "s", minItems));
            }
            if (maxItems != null) {
                if (maxItems >= ValueSynthesizer.MAX_ARRAY_ITEMS) {
                    skip("%s with more than maxItems %d".formatted(label, maxItems), "maxItems is at or above the "
                            + "generator's limit of %d items (no oversized requests)".formatted(ValueSynthesizer.MAX_ARRAY_ITEMS));
                    return;
                }
                int more = maxItems + 1;
                mutation(target, "too-many-items", "%s with more than maxItems %d".formatted(label, maxItems),
                        Category.ARRAY_LENGTH, arraysOfSize(target, base, more), Set.of("maxItems"),
                        value -> "%s has maxItems %d; an array of %d items is sent.".formatted(label, maxItems, more));
                acceptance(target, "at-max-items", "%s with exactly maxItems %d".formatted(label, maxItems),
                        Category.ARRAY_LENGTH, arraysOfSize(target, base, maxItems),
                        value -> "Boundary check: %d item%s satisfy maxItems %d, so the request must succeed."
                                .formatted(maxItems, maxItems == 1 ? "" : "s", maxItems));
            }
        }

        /** Arrays of {@code size} items: a prefix of the valid array, then freshly synthesized ones. */
        private List<Object> arraysOfSize(Target target, List<?> base, int size) {
            List<Object> candidates = new ArrayList<>();
            if (base.size() >= size) {
                candidates.add(new ArrayList<Object>(base.subList(0, size)));
            }
            ValueSynthesizer.Result built = synthesizer.arrayOfSize(target.schema(), size, target.label());
            if (built.ok()) {
                candidates.add(built.value());
            }
            if (!base.isEmpty() && base.size() < size) {
                List<Object> repeated = new ArrayList<>(base);
                while (repeated.size() < size) {
                    repeated.add(JsonValues.deepCopy(base.getFirst()));
                }
                candidates.add(repeated);
            }
            return candidates;
        }

        /**
         * Adds the first candidate that breaks exactly the targeted constraint (and nothing else), or
         * records why no candidate qualifies.
         */
        private void mutation(Target target, String kind, String name, Category category, List<Object> candidates,
                Set<String> keywords, Function<Object, String> rationale) {
            String reason = "no candidate value is available";
            for (Object candidate : candidates) {
                if ("path".equals(target.scope()) && "".equals(candidate)) {
                    reason = "an empty path segment would change the route";
                    continue;
                }
                SchemaValidator.Result result = validate(target, candidate);
                if (result.onlyViolates(target.pointer(), keywords)) {
                    add(target.scope() + "." + kind + ":" + target.id(), name, category, apply(target, candidate),
                            target.scope().equals("path") ? responses.rejection(List.of("400", "404", "422"))
                                    : responses.rejection(List.of("400", "422")),
                            rationale.apply(candidate), target.location());
                    return;
                }
                reason = otherFault(result, target.pointer(), keywords);
            }
            skip(name, reason);
        }

        /** Adds the first candidate that is fully valid (a boundary that must be accepted). */
        private void acceptance(Target target, String kind, String name, Category category, List<Object> candidates,
                Function<Object, String> rationale) {
            for (Object candidate : candidates) {
                if (JsonValues.jsonEquals(candidate, target.base())) {
                    return; // the valid request already uses this boundary value
                }
                if ("path".equals(target.scope()) && "".equals(candidate)) {
                    continue;
                }
                if (validate(target, candidate).valid()) {
                    add(target.scope() + "." + kind + ":" + target.id(), name, category, apply(target, candidate),
                            responses.success(), rationale.apply(candidate), target.location());
                    return;
                }
            }
            skip(name, "no value at this boundary also satisfies the other constraints");
        }

        private SchemaValidator.Result validate(Target target, Object value) {
            if (target.body()) {
                return validator.validate(Pointers.with(body.complete(), target.id(), value), body.schema());
            }
            return validator.validate(value, target.schema());
        }

        private RequestTemplate apply(Target target, Object value) {
            return switch (target.scope()) {
                case "body" -> complete.withBody(Body.json(body.contentType(),
                        Pointers.with(body.complete(), target.id(), value)));
                case "query" -> complete.withQuery(target.id(), value);
                case "path" -> complete.withPathParam(target.id(), value);
                default -> complete.withHeader(target.id(), headerValue(value));
            };
        }

        private static String otherFault(SchemaValidator.Result result, String pointer, Set<String> keywords) {
            if (result.valid()) {
                return "the changed value is still valid, so the request would not test what it claims";
            }
            SchemaValidator.Violation other = result.violations().stream()
                    .filter(violation -> !violation.pointer().equals(pointer) || !keywords.contains(violation.keyword()))
                    .findFirst().orElse(result.violations().getFirst());
            String where = other.pointer().isEmpty() ? "" : Pointers.fieldName(other.pointer()) + ": ";
            return "the change would also break another constraint (" + where + other.message() + "), so the "
                    + "outcome could not be attributed to one cause";
        }

        // --- Bookkeeping ---

        private void add(String scenarioKey, String name, Category category, RequestTemplate request,
                Expectations expectations, String rationale, String location) {
            String key = boundedKey(scenarioKey);
            if (!scenarioKeys.add(key)) {
                return;
            }
            if (tests.size() >= MAX_TESTS_PER_OPERATION) {
                overflow++;
                return;
            }
            tests.add(new Definition(operationKey, key, truncateText(name, 200), category, risk, request, expectations,
                    rationale, truncateText(location, 1000), tests.size(), VERSION));
        }

        private void skip(String scenario, String reason) {
            skipped.add(new Skipped(operationKey, scenario, reason));
        }

        private static String fieldLabel(String pointer) {
            return "field '" + Pointers.fieldName(pointer) + "'";
        }
    }

    // --- Text helpers ---

    static String headerValue(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(DeterministicTestGenerator::scalarText).toList());
        }
        return scalarText(value);
    }

    private static String scalarText(Object value) {
        if (value instanceof Number number) {
            return JsonValues.decimal(number).toPlainString();
        }
        return String.valueOf(value);
    }

    static String preview(Object value) {
        String text = value instanceof String string ? "\"" + string + "\"" : JSON.writeValueAsString(value);
        return text.length() > 60 ? text.substring(0, 57) + "…" : text;
    }

    static String article(String word) {
        return "aeiou".indexOf(word.charAt(0)) >= 0 ? "an" : "a";
    }

    static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String truncate(String value, int codePoints) {
        int length = value.codePointCount(0, value.length());
        return length <= codePoints ? value : value.substring(0, value.offsetByCodePoints(0, codePoints));
    }

    /** {@code value} extended to {@code codePoints} by repeating its last character (or "x"). */
    private static String pad(String value, int codePoints) {
        int length = value.codePointCount(0, value.length());
        if (length >= codePoints) {
            return truncate(value, codePoints);
        }
        String filler = value.isEmpty() ? "x" : new String(Character.toChars(value.codePointBefore(value.length())));
        return value + filler.repeat(codePoints - length);
    }

    private static String truncateText(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** Scenario keys longer than the column keep a readable prefix plus a hash of the full key. */
    private static String boundedKey(String key) {
        if (key.length() <= MAX_SCENARIO_KEY) {
            return key;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
            return key.substring(0, MAX_SCENARIO_KEY - 17) + "#" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", ex);
        }
    }
}
