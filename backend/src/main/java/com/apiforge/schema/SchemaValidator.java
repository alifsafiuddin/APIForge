package com.apiforge.schema;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.apiforge.common.json.JsonValues;

/**
 * A JSON Schema validator for the keywords OpenAPI 3.0 and 3.1 documents use, shared by test
 * generation (request values) and contract validation (response bodies).
 *
 * <p>Supported: type (incl. 3.0 nullable and 3.1 type arrays), enum, const, numeric bounds (3.0
 * and 3.1 exclusivity), multipleOf in exact decimals, string length in code points, pattern
 * (unanchored, with a backtracking budget), array length, uniqueness and items, properties,
 * required, additionalProperties, property counts, allOf, oneOf, anyOf and not, plus the
 * direction-specific rules for readOnly and writeOnly properties. In the response direction the
 * common formats are asserted too. Any other assertion keyword is reported as unevaluated, never
 * silently treated as satisfied.
 */
public final class SchemaValidator {

    /** Which side of an exchange the value is: decides the readOnly / writeOnly rules and format checks. */
    public enum Direction {
        /** readOnly properties must not be sent and are not required; formats are annotations. */
        REQUEST,
        /** writeOnly properties must not be returned and are not required; common formats are asserted. */
        RESPONSE
    }

    /**
     * @param pointer  JSON pointer of the offending value inside the validated instance
     * @param expected what the schema requires, in words ("integer", "≤ 100", "one of a, b")
     * @param actual   what was found ("string \"12\"", "missing", "6 items")
     */
    public record Violation(String pointer, String keyword, String message, String expected, String actual) {

        public Violation(String pointer, String keyword, String message) {
            this(pointer, keyword, message, null, null);
        }

        /** The pointer as a JSON path ({@code $.items[2].price}). */
        public String jsonPath() {
            return JsonPaths.fromPointer(pointer);
        }
    }

    /** Violations found, plus assertion keywords that could not be evaluated. */
    public record Result(List<Violation> violations, Set<String> unevaluated) {

        public boolean valid() {
            return violations.isEmpty();
        }

        /** Exactly one fault: every violation is at {@code pointer} and has one of {@code keywords}. */
        public boolean onlyViolates(String pointer, Set<String> keywords) {
            return !violations.isEmpty() && violations.stream()
                    .allMatch(violation -> violation.pointer().equals(pointer) && keywords.contains(violation.keyword()));
        }
    }

    private record Context(Result result, Direction direction) {
    }

    private static final Set<String> UNSUPPORTED = Set.of("if", "then", "else", "dependentRequired",
            "dependentSchemas", "dependencies", "patternProperties", "propertyNames", "unevaluatedProperties",
            "unevaluatedItems", "contains", "minContains", "maxContains", "prefixItems", "$dynamicRef",
            "$recursiveRef");
    private static final int MAX_DEPTH = 64;
    private static final Pattern UUID = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern IPV4 = Pattern.compile(
            "^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$");
    private static final Pattern HOSTNAME = Pattern.compile(
            "^(?=.{1,253}$)([A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$");
    private static final BigDecimal INT32_MIN = BigDecimal.valueOf(Integer.MIN_VALUE);
    private static final BigDecimal INT32_MAX = BigDecimal.valueOf(Integer.MAX_VALUE);
    private static final BigDecimal INT64_MIN = BigDecimal.valueOf(Long.MIN_VALUE);
    private static final BigDecimal INT64_MAX = BigDecimal.valueOf(Long.MAX_VALUE);

    private final GuardedPattern patterns;

    public SchemaValidator(GuardedPattern patterns) {
        this.patterns = patterns;
    }

    /** Request direction (generated test data). */
    public Result validate(Object value, Schema schema) {
        return validate(value, schema, Direction.REQUEST);
    }

    public Result validate(Object value, Schema schema, Direction direction) {
        Context context = new Context(new Result(new ArrayList<>(), new TreeSet<>()), direction);
        check(value, schema, "", context, 0);
        return context.result();
    }

    private void check(Object value, Schema schema, String pointer, Context context, int depth) {
        Result result = context.result();
        if (depth > MAX_DEPTH) {
            result.unevaluated().add("nesting beyond " + MAX_DEPTH + " levels");
            return;
        }
        if (schema.problem() != null) {
            result.unevaluated().add(schema.problem());
            return;
        }
        if (schema.isNever()) {
            fail(result, pointer, "false", "no value is allowed here", "no value", describe(value));
            return;
        }
        for (String keyword : schema.raw().keySet()) {
            if (UNSUPPORTED.contains(keyword)) {
                result.unevaluated().add(keyword);
            }
        }
        if (schema.raw().get("allOf") instanceof List<?> members) {
            for (int index = 0; index < members.size(); index++) {
                check(value, schema.resolver().resolve(members.get(index), schema.location() + "/allOf/" + index),
                        pointer, context, depth + 1);
            }
        }
        Schema own = schema.own();
        if (!checkType(value, own, pointer, result)) {
            return;
        }
        checkValue(value, own, pointer, result);
        switch (value) {
            case Number number -> checkNumber(JsonValues.decimal(number), own, pointer, context);
            case String text -> checkString(text, own, pointer, context);
            case List<?> list -> checkArray(list, own, pointer, context, depth);
            case Map<?, ?> map -> checkObject(map, own, pointer, context, depth);
            case null, default -> {
                // null and booleans have no further keywords
            }
        }
        checkComposition(value, own, pointer, context, depth);
    }

    /** @return whether further keywords apply (false after a type mismatch or an allowed null) */
    private static boolean checkType(Object value, Schema schema, String pointer, Result result) {
        Set<String> types = schema.declaredTypes();
        if (types.isEmpty()) {
            return true;
        }
        if (!matchesAny(value, types)) {
            String expected = String.join(" or ", types);
            fail(result, pointer, "type", "expected %s but found %s".formatted(expected, JsonValues.typeName(value)),
                    expected, describe(value));
            return false;
        }
        return value != null;
    }

    public static boolean matchesAny(Object value, Set<String> types) {
        for (String type : types) {
            boolean matches = switch (type) {
                case "null" -> value == null;
                case "boolean" -> value instanceof Boolean;
                case "string" -> value instanceof String;
                case "object" -> value instanceof Map<?, ?>;
                case "array" -> value instanceof List<?>;
                case "number" -> value instanceof Number;
                case "integer" -> value instanceof Number number && isIntegral(JsonValues.decimal(number));
                default -> false;
            };
            if (matches) {
                return true;
            }
        }
        return false;
    }

    public static boolean isIntegral(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    private static void checkValue(Object value, Schema schema, String pointer, Result result) {
        List<?> allowed = schema.enumValues();
        if (allowed != null && allowed.stream().noneMatch(candidate -> JsonValues.jsonEquals(candidate, value))) {
            fail(result, pointer, "enum", "value is not one of the allowed values", "one of " + list(allowed),
                    describe(value));
        }
        if (schema.has("const") && !JsonValues.jsonEquals(schema.constValue(), value)) {
            fail(result, pointer, "const", "value differs from the required constant", describe(schema.constValue()),
                    describe(value));
        }
    }

    private static void checkNumber(BigDecimal value, Schema schema, String pointer, Context context) {
        Result result = context.result();
        String actual = value.toPlainString();
        Schema.Bound lower = schema.lower();
        if (lower != null) {
            int comparison = value.compareTo(lower.value());
            if (comparison < 0 || (lower.exclusive() && comparison == 0)) {
                String expected = (lower.exclusive() ? "> " : "≥ ") + lower.value().toPlainString();
                fail(result, pointer, lower.exclusive() ? "exclusiveMinimum" : "minimum", "must be " + expected, expected,
                        actual);
            }
        }
        Schema.Bound upper = schema.upper();
        if (upper != null) {
            int comparison = value.compareTo(upper.value());
            if (comparison > 0 || (upper.exclusive() && comparison == 0)) {
                String expected = (upper.exclusive() ? "< " : "≤ ") + upper.value().toPlainString();
                fail(result, pointer, upper.exclusive() ? "exclusiveMaximum" : "maximum", "must be " + expected, expected,
                        actual);
            }
        }
        BigDecimal multipleOf = schema.multipleOf();
        if (multipleOf != null && value.remainder(multipleOf).signum() != 0) {
            fail(result, pointer, "multipleOf", "must be a multiple of " + multipleOf.toPlainString(),
                    "multiple of " + multipleOf.toPlainString(), actual);
        }
        if (context.direction() == Direction.RESPONSE && isIntegral(value)) {
            String format = schema.format();
            if ("int32".equals(format) && (value.compareTo(INT32_MIN) < 0 || value.compareTo(INT32_MAX) > 0)) {
                fail(result, pointer, "format", "must fit a 32-bit integer (format int32)", "int32", actual);
            } else if ("int64".equals(format) && (value.compareTo(INT64_MIN) < 0 || value.compareTo(INT64_MAX) > 0)) {
                fail(result, pointer, "format", "must fit a 64-bit integer (format int64)", "int64", actual);
            }
        }
    }

    private void checkString(String value, Schema schema, String pointer, Context context) {
        Result result = context.result();
        int length = value.codePointCount(0, value.length());
        Integer minLength = schema.count("minLength");
        if (minLength != null && length < minLength) {
            fail(result, pointer, "minLength", "must have at least " + count(minLength, "character"),
                    "at least " + count(minLength, "character"), count(length, "character"));
        }
        Integer maxLength = schema.count("maxLength");
        if (maxLength != null && length > maxLength) {
            fail(result, pointer, "maxLength", "must have at most " + count(maxLength, "character"),
                    "at most " + count(maxLength, "character"), count(length, "character"));
        }
        String pattern = schema.pattern();
        if (pattern != null) {
            Optional<Boolean> matches = patterns.matches(pattern, value);
            if (matches.isEmpty()) {
                result.unevaluated().add("pattern");
            } else if (!matches.get()) {
                fail(result, pointer, "pattern", "must match the pattern " + pattern, "matches " + pattern,
                        describe(value));
            }
        }
        if (context.direction() == Direction.RESPONSE && schema.format() != null && !validFormat(schema.format(), value)) {
            fail(result, pointer, "format", "is not a valid " + schema.format(), "format " + schema.format(),
                    describe(value));
        }
    }

    /** "1 item", "3 items", "1 property". */
    static String count(long n, String noun) {
        if (n == 1) {
            return "1 " + noun;
        }
        return n + " " + (noun.endsWith("y") ? noun.substring(0, noun.length() - 1) + "ies" : noun + "s");
    }

    /** The formats contract validation asserts; unknown formats are annotations and always valid. */
    static boolean validFormat(String format, String value) {
        try {
            return switch (format) {
                case "date" -> {
                    LocalDate.parse(value);
                    yield true;
                }
                case "date-time" -> {
                    OffsetDateTime.parse(value);
                    yield true;
                }
                case "time" -> {
                    LocalTime.parse(value.replaceAll("(Z|[+-]\\d{2}:\\d{2})$", ""));
                    yield true;
                }
                case "uuid" -> UUID.matcher(value).matches();
                case "email" -> EMAIL.matcher(value).matches();
                case "ipv4" -> IPV4.matcher(value).matches();
                case "ipv6" -> value.contains(":") && value.matches("^[0-9a-fA-F:.]+$") && validIpv6(value);
                case "hostname" -> HOSTNAME.matcher(value).matches();
                case "uri" -> new URI(value).isAbsolute();
                case "uri-reference" -> {
                    new URI(value);
                    yield true;
                }
                case "byte" -> {
                    Base64.getDecoder().decode(value);
                    yield true;
                }
                default -> true;
            };
        } catch (DateTimeParseException | URISyntaxException | IllegalArgumentException ex) {
            return false;
        }
    }

    private static boolean validIpv6(String value) {
        try {
            return java.net.InetAddress.getByName(value) instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException ex) {
            return false;
        }
    }

    private void checkArray(List<?> list, Schema schema, String pointer, Context context, int depth) {
        Result result = context.result();
        Integer minItems = schema.count("minItems");
        if (minItems != null && list.size() < minItems) {
            fail(result, pointer, "minItems", "must have at least " + count(minItems, "item"),
                    "at least " + count(minItems, "item"), count(list.size(), "item"));
        }
        Integer maxItems = schema.count("maxItems");
        if (maxItems != null && list.size() > maxItems) {
            fail(result, pointer, "maxItems", "must have at most " + count(maxItems, "item"),
                    "at most " + count(maxItems, "item"), count(list.size(), "item"));
        }
        if (Boolean.TRUE.equals(schema.merged().get("uniqueItems")) && hasDuplicates(list)) {
            fail(result, pointer, "uniqueItems", "items must be unique", "unique items", "duplicate items");
        }
        if (schema.has("items")) {
            Schema items = schema.items();
            for (int index = 0; index < list.size(); index++) {
                check(list.get(index), items, pointer + "/" + index, context, depth + 1);
            }
        }
    }

    private static boolean hasDuplicates(List<?> list) {
        for (int first = 0; first < list.size(); first++) {
            for (int second = first + 1; second < list.size(); second++) {
                if (JsonValues.jsonEquals(list.get(first), list.get(second))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void checkObject(Map<?, ?> map, Schema schema, String pointer, Context context, int depth) {
        Result result = context.result();
        boolean request = context.direction() == Direction.REQUEST;
        for (String property : schema.required()) {
            if (map.containsKey(property)) {
                continue;
            }
            Schema propertySchema = schema.property(property);
            boolean exempt = request ? propertySchema.readOnly() : propertySchema.writeOnly();
            if (!exempt) {
                fail(result, Pointers.child(pointer, property), "required",
                        "required property '%s' is missing".formatted(property), "property '" + property + "'",
                        "missing");
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String property = String.valueOf(entry.getKey());
            String child = Pointers.child(pointer, property);
            if (schema.declaresProperty(property)) {
                Schema propertySchema = schema.property(property);
                if (request && propertySchema.readOnly()) {
                    fail(result, child, "readOnly", "'%s' is read-only and must not be sent".formatted(property),
                            "not sent (readOnly)", describe(entry.getValue()));
                } else if (!request && propertySchema.writeOnly()) {
                    fail(result, child, "writeOnly", "'%s' is write-only and must not be returned".formatted(property),
                            "not returned (writeOnly)", describe(entry.getValue()));
                }
                check(entry.getValue(), propertySchema, child, context, depth + 1);
            } else if (!schema.allowsAdditionalProperties()) {
                fail(result, child, "additionalProperties", "'%s' is not a declared property".formatted(property),
                        "only declared properties", "property '" + property + "'");
            } else if (schema.merged().get("additionalProperties") instanceof Map<?, ?>) {
                check(entry.getValue(), schema.property(property), child, context, depth + 1);
            }
        }
        Integer minProperties = schema.count("minProperties");
        if (minProperties != null && map.size() < minProperties) {
            fail(result, pointer, "minProperties", "must have at least " + count(minProperties, "property"),
                    "at least " + count(minProperties, "property"), count(map.size(), "property"));
        }
        Integer maxProperties = schema.count("maxProperties");
        if (maxProperties != null && map.size() > maxProperties) {
            fail(result, pointer, "maxProperties", "must have at most " + count(maxProperties, "property"),
                    "at most " + count(maxProperties, "property"), count(map.size(), "property"));
        }
    }

    private void checkComposition(Object value, Schema schema, String pointer, Context context, int depth) {
        Result result = context.result();
        List<?> oneOf = schema.oneOf();
        if (!oneOf.isEmpty()) {
            Outcomes outcomes = alternatives(value, schema, oneOf, "/oneOf/", context, depth);
            if (outcomes.uncertain()) {
                result.unevaluated().add("oneOf");
            } else if (outcomes.matching() != 1) {
                fail(result, pointer, "oneOf", "must match exactly one alternative (matches %d)".formatted(outcomes.matching()),
                        "exactly one of " + oneOf.size() + " alternatives", outcomes.matching() + " matching");
            }
        }
        List<?> anyOf = schema.anyOf();
        if (!anyOf.isEmpty()) {
            Outcomes outcomes = alternatives(value, schema, anyOf, "/anyOf/", context, depth);
            if (outcomes.matching() == 0) {
                if (outcomes.uncertain()) {
                    result.unevaluated().add("anyOf");
                } else {
                    fail(result, pointer, "anyOf", "must match at least one alternative",
                            "at least one of " + anyOf.size() + " alternatives", "none matching");
                }
            }
        }
        if (schema.merged().containsKey("not")) {
            Context inner = new Context(new Result(new ArrayList<>(), new TreeSet<>()), context.direction());
            check(value, schema.resolver().resolve(schema.merged().get("not"), schema.location() + "/not"), "", inner,
                    depth + 1);
            if (!inner.result().unevaluated().isEmpty()) {
                result.unevaluated().add("not");
            } else if (inner.result().valid()) {
                fail(result, pointer, "not", "must not match the 'not' schema", "not matching", "matching");
            }
        }
    }

    private record Outcomes(int matching, boolean uncertain) {
    }

    private Outcomes alternatives(Object value, Schema schema, List<?> alternatives, String segment, Context context,
            int depth) {
        int matching = 0;
        boolean uncertain = false;
        for (int index = 0; index < alternatives.size(); index++) {
            Context inner = new Context(new Result(new ArrayList<>(), new TreeSet<>()), context.direction());
            check(value, schema.resolver().resolve(alternatives.get(index), schema.location() + segment + index), "",
                    inner, depth + 1);
            if (inner.result().valid()) {
                if (inner.result().unevaluated().isEmpty()) {
                    matching++;
                } else {
                    uncertain = true;
                }
            }
        }
        return new Outcomes(matching, uncertain);
    }

    private static void fail(Result result, String pointer, String keyword, String message, String expected,
            String actual) {
        result.violations().add(new Violation(pointer, keyword, message, expected, actual));
    }

    /** A short description of a JSON value for "actual" fields: type and (truncated) value. */
    public static String describe(Object value) {
        String type = JsonValues.typeName(value);
        return switch (value) {
            case null -> "null";
            case String text -> "string \"" + (text.length() > 60 ? text.substring(0, 57) + "…" : text) + "\"";
            case Number number -> {
                BigDecimal decimal = JsonValues.decimal(number);
                yield (isIntegral(decimal) ? "integer " : "number ")
                        + (decimal.abs().compareTo(new BigDecimal(BigInteger.TEN.pow(15))) > 0 ? decimal.toString()
                                : decimal.toPlainString());
            }
            case Boolean bool -> "boolean " + bool;
            case Map<?, ?> map -> "object with " + map.size() + " propert" + (map.size() == 1 ? "y" : "ies");
            case List<?> list -> "array of " + list.size() + " item" + (list.size() == 1 ? "" : "s");
            default -> type;
        };
    }

    private static String list(List<?> values) {
        List<String> items = values.stream().limit(10).map(value -> value instanceof String text ? text : String.valueOf(value))
                .toList();
        return String.join(", ", items) + (values.size() > 10 ? ", …" : "");
    }
}
