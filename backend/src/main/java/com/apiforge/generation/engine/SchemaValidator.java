package com.apiforge.generation.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.apiforge.common.json.JsonValues;

/**
 * Checks generated request values against a schema, so that every generated test is what it
 * claims to be: valid requests satisfy the schema, and each negative request breaks exactly the
 * constraint it targets.
 *
 * <p>Supports the keywords that matter for request data: type (incl. 3.0 nullable), enum,
 * const, numeric bounds (3.0 and 3.1 exclusivity), multipleOf, string length, pattern, array
 * length and uniqueness, items, properties, required, additionalProperties, property counts,
 * allOf, oneOf, anyOf, not and readOnly (properties marked readOnly must not be sent). Any other
 * assertion keyword is reported as unevaluated rather than silently ignored. {@code format} is an
 * annotation and is not asserted.
 */
final class SchemaValidator {

    /** @param pointer JSON pointer of the offending value inside the validated instance */
    record Violation(String pointer, String keyword, String message) {
    }

    /** Violations found, plus assertion keywords that could not be evaluated. */
    record Result(List<Violation> violations, Set<String> unevaluated) {

        boolean valid() {
            return violations.isEmpty();
        }

        /** Exactly one fault: every violation is at {@code pointer} and has one of {@code keywords}. */
        boolean onlyViolates(String pointer, Set<String> keywords) {
            return !violations.isEmpty() && violations.stream()
                    .allMatch(violation -> violation.pointer().equals(pointer) && keywords.contains(violation.keyword()));
        }
    }

    private static final Set<String> UNSUPPORTED = Set.of("if", "then", "else", "dependentRequired",
            "dependentSchemas", "dependencies", "patternProperties", "propertyNames", "unevaluatedProperties",
            "unevaluatedItems", "contains", "minContains", "maxContains", "prefixItems", "$dynamicRef",
            "$recursiveRef");
    private static final int MAX_DEPTH = 64;

    private final GuardedPattern patterns;

    SchemaValidator(GuardedPattern patterns) {
        this.patterns = patterns;
    }

    Result validate(Object value, Schema schema) {
        Result result = new Result(new ArrayList<>(), new TreeSet<>());
        check(value, schema, "", result, 0);
        return result;
    }

    private void check(Object value, Schema schema, String pointer, Result result, int depth) {
        if (depth > MAX_DEPTH) {
            result.unevaluated().add("nesting beyond " + MAX_DEPTH + " levels");
            return;
        }
        if (schema.problem() != null) {
            result.unevaluated().add(schema.problem());
            return;
        }
        if (schema.isNever()) {
            fail(result, pointer, "false", "no value is allowed here");
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
                        pointer, result, depth + 1);
            }
        }
        Schema own = schema.own();
        if (!checkType(value, own, pointer, result)) {
            return;
        }
        checkValue(value, own, pointer, result);
        switch (value) {
            case Number number -> checkNumber(JsonValues.decimal(number), own, pointer, result);
            case String text -> checkString(text, own, pointer, result);
            case List<?> list -> checkArray(list, own, pointer, result, depth);
            case Map<?, ?> map -> checkObject(map, own, pointer, result, depth);
            default -> {
                // null and booleans have no further keywords
            }
        }
        checkComposition(value, own, pointer, result, depth);
    }

    /** @return whether further keywords apply (false after a type mismatch or an allowed null) */
    private boolean checkType(Object value, Schema schema, String pointer, Result result) {
        Set<String> types = schema.declaredTypes();
        if (types.isEmpty()) {
            return true;
        }
        if (!matchesAny(value, types)) {
            fail(result, pointer, "type", "expected %s but found %s".formatted(String.join(" or ", types),
                    JsonValues.typeName(value)));
            return false;
        }
        return value != null;
    }

    static boolean matchesAny(Object value, Set<String> types) {
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

    static boolean isIntegral(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    private static void checkValue(Object value, Schema schema, String pointer, Result result) {
        List<?> allowed = schema.enumValues();
        if (allowed != null && allowed.stream().noneMatch(candidate -> JsonValues.jsonEquals(candidate, value))) {
            fail(result, pointer, "enum", "value is not one of the allowed values");
        }
        if (schema.has("const") && !JsonValues.jsonEquals(schema.constValue(), value)) {
            fail(result, pointer, "const", "value differs from the required constant");
        }
    }

    private static void checkNumber(BigDecimal value, Schema schema, String pointer, Result result) {
        Schema.Bound lower = schema.lower();
        if (lower != null) {
            int comparison = value.compareTo(lower.value());
            if (comparison < 0 || (lower.exclusive() && comparison == 0)) {
                fail(result, pointer, lower.exclusive() ? "exclusiveMinimum" : "minimum",
                        "must be %s %s".formatted(lower.exclusive() ? ">" : ">=", lower.value().toPlainString()));
            }
        }
        Schema.Bound upper = schema.upper();
        if (upper != null) {
            int comparison = value.compareTo(upper.value());
            if (comparison > 0 || (upper.exclusive() && comparison == 0)) {
                fail(result, pointer, upper.exclusive() ? "exclusiveMaximum" : "maximum",
                        "must be %s %s".formatted(upper.exclusive() ? "<" : "<=", upper.value().toPlainString()));
            }
        }
        BigDecimal multipleOf = schema.multipleOf();
        if (multipleOf != null && value.remainder(multipleOf).signum() != 0) {
            fail(result, pointer, "multipleOf", "must be a multiple of " + multipleOf.toPlainString());
        }
    }

    private void checkString(String value, Schema schema, String pointer, Result result) {
        int length = value.codePointCount(0, value.length());
        Integer minLength = schema.count("minLength");
        if (minLength != null && length < minLength) {
            fail(result, pointer, "minLength", "must have at least %d characters".formatted(minLength));
        }
        Integer maxLength = schema.count("maxLength");
        if (maxLength != null && length > maxLength) {
            fail(result, pointer, "maxLength", "must have at most %d characters".formatted(maxLength));
        }
        String pattern = schema.pattern();
        if (pattern != null) {
            Optional<Boolean> matches = patterns.matches(pattern, value);
            if (matches.isEmpty()) {
                result.unevaluated().add("pattern");
            } else if (!matches.get()) {
                fail(result, pointer, "pattern", "must match the pattern " + pattern);
            }
        }
    }

    private void checkArray(List<?> list, Schema schema, String pointer, Result result, int depth) {
        Integer minItems = schema.count("minItems");
        if (minItems != null && list.size() < minItems) {
            fail(result, pointer, "minItems", "must have at least %d items".formatted(minItems));
        }
        Integer maxItems = schema.count("maxItems");
        if (maxItems != null && list.size() > maxItems) {
            fail(result, pointer, "maxItems", "must have at most %d items".formatted(maxItems));
        }
        if (Boolean.TRUE.equals(schema.merged().get("uniqueItems")) && hasDuplicates(list)) {
            fail(result, pointer, "uniqueItems", "items must be unique");
        }
        if (schema.has("items")) {
            Schema items = schema.items();
            for (int index = 0; index < list.size(); index++) {
                check(list.get(index), items, pointer + "/" + index, result, depth + 1);
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

    private void checkObject(Map<?, ?> map, Schema schema, String pointer, Result result, int depth) {
        for (String property : schema.required()) {
            if (!map.containsKey(property) && !schema.property(property).readOnly()) {
                fail(result, Pointers.child(pointer, property), "required",
                        "required property '%s' is missing".formatted(property));
            }
        }
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String property = String.valueOf(entry.getKey());
            String child = Pointers.child(pointer, property);
            if (schema.declaresProperty(property)) {
                Schema propertySchema = schema.property(property);
                if (propertySchema.readOnly()) {
                    fail(result, child, "readOnly", "'%s' is read-only and must not be sent".formatted(property));
                }
                check(entry.getValue(), propertySchema, child, result, depth + 1);
            } else if (!schema.allowsAdditionalProperties()) {
                fail(result, child, "additionalProperties", "'%s' is not a declared property".formatted(property));
            } else if (schema.merged().get("additionalProperties") instanceof Map<?, ?>) {
                check(entry.getValue(), schema.property(property), child, result, depth + 1);
            }
        }
        Integer minProperties = schema.count("minProperties");
        if (minProperties != null && map.size() < minProperties) {
            fail(result, pointer, "minProperties", "must have at least %d properties".formatted(minProperties));
        }
        Integer maxProperties = schema.count("maxProperties");
        if (maxProperties != null && map.size() > maxProperties) {
            fail(result, pointer, "maxProperties", "must have at most %d properties".formatted(maxProperties));
        }
    }

    private void checkComposition(Object value, Schema schema, String pointer, Result result, int depth) {
        List<?> oneOf = schema.oneOf();
        if (!oneOf.isEmpty()) {
            Outcomes outcomes = alternatives(value, schema, oneOf, "/oneOf/", depth);
            if (outcomes.uncertain()) {
                result.unevaluated().add("oneOf");
            } else if (outcomes.matching() != 1) {
                fail(result, pointer, "oneOf",
                        "must match exactly one alternative (matches %d)".formatted(outcomes.matching()));
            }
        }
        List<?> anyOf = schema.anyOf();
        if (!anyOf.isEmpty()) {
            Outcomes outcomes = alternatives(value, schema, anyOf, "/anyOf/", depth);
            if (outcomes.matching() == 0) {
                if (outcomes.uncertain()) {
                    result.unevaluated().add("anyOf");
                } else {
                    fail(result, pointer, "anyOf", "must match at least one alternative");
                }
            }
        }
        if (schema.merged().containsKey("not")) {
            Result inner = new Result(new ArrayList<>(), new TreeSet<>());
            check(value, schema.resolver().resolve(schema.merged().get("not"), schema.location() + "/not"), "", inner,
                    depth + 1);
            if (!inner.unevaluated().isEmpty()) {
                result.unevaluated().add("not");
            } else if (inner.valid()) {
                fail(result, pointer, "not", "must not match the 'not' schema");
            }
        }
    }

    private record Outcomes(int matching, boolean uncertain) {
    }

    private Outcomes alternatives(Object value, Schema schema, List<?> alternatives, String segment, int depth) {
        int matching = 0;
        boolean uncertain = false;
        for (int index = 0; index < alternatives.size(); index++) {
            Result inner = new Result(new ArrayList<>(), new TreeSet<>());
            check(value, schema.resolver().resolve(alternatives.get(index), schema.location() + segment + index), "",
                    inner, depth + 1);
            if (inner.valid()) {
                if (inner.unevaluated().isEmpty()) {
                    matching++;
                } else {
                    uncertain = true;
                }
            }
        }
        return new Outcomes(matching, uncertain);
    }

    private static void fail(Result result, String pointer, String keyword, String message) {
        result.violations().add(new Violation(pointer, keyword, message));
    }
}
