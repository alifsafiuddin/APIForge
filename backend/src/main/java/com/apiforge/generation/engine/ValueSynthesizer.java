package com.apiforge.generation.engine;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import com.apiforge.common.json.JsonValues;
import com.github.curiousoddman.rgxgen.RgxGen;
import com.github.curiousoddman.rgxgen.config.RgxGenOption;
import com.github.curiousoddman.rgxgen.config.RgxGenProperties;

/**
 * Builds values that satisfy a schema, deterministically. Candidates are tried in a fixed order —
 * const, documented examples, default, enum values, composition alternatives, then values
 * synthesized from the type and its constraints — and the first one the {@link SchemaValidator}
 * accepts wins. The same schema therefore always yields the same value, and a value is never
 * returned unless it was validated.
 */
final class ValueSynthesizer {

    /** Required properties nested deeper than this make a value impossible (recursive schemas). */
    static final int MAX_DEPTH = 8;
    /** Optional properties and array items are only added down to this depth in complete mode. */
    static final int COMPLETE_DEPTH = 4;
    /** Arrays longer than this are not built (no oversized bodies). */
    static final int MAX_ARRAY_ITEMS = 100;
    private static final int PATTERN_ATTEMPTS = 25;
    private static final int PATTERN_REPETITION = 6;

    /** A value, or why none could be built. */
    record Result(Object value, String failure) {

        static Result of(Object value) {
            return new Result(value, null);
        }

        static Result failed(String failure) {
            return new Result(null, failure);
        }

        boolean ok() {
            return failure == null;
        }
    }

    private final SchemaValidator validator;
    private final GuardedPattern patterns;

    ValueSynthesizer(SchemaValidator validator, GuardedPattern patterns) {
        this.validator = validator;
        this.patterns = patterns;
    }

    /**
     * @param complete include optional properties (and one array item) instead of the required minimum
     * @param seed     distinguishes positions so that, for example, unique array items differ
     */
    Result valid(Schema schema, boolean complete, String seed) {
        return build(schema, complete, seed, 0, 0);
    }

    /** A valid value that differs from the one {@link #valid} returns (for uniqueItems). */
    Result variant(Schema schema, boolean complete, String seed, int variant) {
        return build(schema, complete, seed, 0, variant);
    }

    private Result build(Schema schema, boolean complete, String seed, int depth, int variant) {
        if (schema.problem() != null) {
            return Result.failed(schema.problem());
        }
        if (schema.isNever()) {
            return Result.failed(schema.describe() + " accepts no value");
        }
        if (depth > MAX_DEPTH) {
            return Result.failed("required properties nest more than " + MAX_DEPTH + " levels deep (recursive schema)");
        }
        String type = schema.primaryType();
        List<Object> candidates = new ArrayList<>();
        if (schema.has("const")) {
            candidates.add(schema.constValue());
        }
        if (variant == 0 && !"object".equals(type)) {
            candidates.addAll(schema.examples());
            if (schema.has("default")) {
                candidates.add(schema.merged().get("default"));
            }
        }
        List<?> allowed = schema.enumValues();
        if (allowed != null) {
            for (int index = 0; index < allowed.size(); index++) {
                candidates.add(allowed.get((index + variant) % allowed.size()));
            }
        }
        Result accepted = firstValid(candidates, schema);
        if (accepted != null) {
            return accepted;
        }
        if (allowed != null) {
            return Result.failed("none of the enum values of " + schema.describe() + " satisfies its other constraints");
        }

        Result composed = fromAlternatives(schema, complete, seed, depth, variant);
        if (composed != null) {
            return composed;
        }

        Result synthesized = switch (type == null ? "any" : type) {
            case "object" -> object(schema, complete, seed, depth, variant);
            case "array" -> array(schema, complete, seed, depth, variant);
            case "string" -> string(schema, seed, variant);
            case "integer" -> number(schema, true, variant);
            case "number" -> number(schema, false, variant);
            case "boolean" -> checked(variant % 2 == 0, schema);
            case "null" -> checked(null, schema);
            default -> checked(variant == 0 ? "sample" : "sample-" + variant, schema);
        };
        if (synthesized.ok() || !"object".equals(type)) {
            return synthesized;
        }
        // Last resort for objects: a documented example of the whole object.
        List<Object> documented = new ArrayList<>(schema.examples());
        if (schema.has("default")) {
            documented.add(schema.merged().get("default"));
        }
        Result example = firstValid(documented, schema);
        return example != null ? example : synthesized;
    }

    private Result firstValid(List<Object> candidates, Schema schema) {
        for (Object candidate : candidates) {
            if (accepted(validator.validate(candidate, schema))) {
                return Result.of(JsonValues.deepCopy(candidate));
            }
        }
        return null;
    }

    private Result checked(Object value, Schema schema) {
        SchemaValidator.Result result = validator.validate(value, schema);
        if (accepted(result)) {
            return Result.of(value);
        }
        return Result.failed(result.valid()
                ? "a pattern of %s cannot be evaluated, so no value is known to match it".formatted(schema.describe())
                : describeFailure(schema, result));
    }

    /**
     * Valid, and every pattern was decided: a string the generator chose must be known to match.
     * (Other unevaluated keywords are reported with the test instead.)
     */
    private static boolean accepted(SchemaValidator.Result result) {
        return result.valid() && !result.unevaluated().contains("pattern");
    }

    private static String describeFailure(Schema schema, SchemaValidator.Result result) {
        SchemaValidator.Violation first = result.violations().getFirst();
        String where = first.pointer().isEmpty() ? "" : " at " + Pointers.fieldName(first.pointer());
        return "no value satisfying %s could be built (%s%s)".formatted(schema.describe(), first.message(), where);
    }

    // --- oneOf / anyOf ---

    private Result fromAlternatives(Schema schema, boolean complete, String seed, int depth, int variant) {
        List<?> oneOf = schema.oneOf();
        List<?> alternatives = oneOf.isEmpty() ? schema.anyOf() : oneOf;
        if (alternatives.isEmpty()) {
            return null;
        }
        String segment = oneOf.isEmpty() ? "/anyOf/" : "/oneOf/";
        String lastFailure = null;
        for (int offset = 0; offset < alternatives.size(); offset++) {
            int index = (offset + variant) % alternatives.size();
            Schema alternative = schema.resolver().alternative(schema, alternatives.get(index),
                    schema.location() + segment + index);
            Result result = build(alternative, complete, seed + segment + index, depth, variant);
            if (!result.ok()) {
                lastFailure = result.failure();
                continue;
            }
            Object value = result.value();
            String discriminator = schema.discriminatorProperty();
            if (discriminator != null && alternative.name() != null && value instanceof Map<?, ?> map) {
                Map<String, Object> tagged = JsonValues.copyObject(map);
                tagged.put(discriminator, schema.discriminatorValue(alternative.name()));
                value = tagged;
            }
            if (validator.validate(value, schema).valid()) {
                return Result.of(value);
            }
            lastFailure = "the value built for alternative %d also matches another alternative".formatted(index + 1);
        }
        return Result.failed("no oneOf/anyOf alternative of %s yields a valid value (%s)".formatted(schema.describe(),
                lastFailure));
    }

    // --- Objects and arrays ---

    private Result object(Schema schema, boolean complete, String seed, int depth, int variant) {
        Map<String, Object> value = new LinkedHashMap<>();
        Set<String> required = new LinkedHashSet<>(schema.required());
        boolean withOptional = complete && depth < COMPLETE_DEPTH;
        List<String> order = new ArrayList<>(required);
        schema.propertyNames().stream().sorted().filter(name -> !required.contains(name)).forEach(order::add);
        List<String> skippedOptional = new ArrayList<>();
        for (String property : order) {
            boolean isRequired = required.contains(property);
            Schema propertySchema = schema.property(property);
            if (propertySchema.readOnly()) {
                continue;
            }
            if (!isRequired && !withOptional) {
                skippedOptional.add(property);
                continue;
            }
            Result result = build(propertySchema, complete, Pointers.child(seed, property), depth + 1, variant);
            if (result.ok()) {
                value.put(property, result.value());
            } else if (isRequired) {
                return Result.failed("required property '%s': %s".formatted(property, result.failure()));
            }
        }
        Integer minProperties = schema.count("minProperties");
        for (String property : skippedOptional) {
            if (minProperties == null || value.size() >= minProperties) {
                break;
            }
            Result result = build(schema.property(property), complete, Pointers.child(seed, property), depth + 1, variant);
            if (result.ok()) {
                value.put(property, result.value());
            }
        }
        for (int extra = 1; minProperties != null && value.size() < minProperties && schema.allowsAdditionalProperties()
                && extra <= minProperties; extra++) {
            String property = "property" + extra;
            Result result = build(schema.property(property), complete, Pointers.child(seed, property), depth + 1, variant);
            if (result.ok() && !value.containsKey(property)) {
                value.put(property, result.value());
            }
        }
        return checked(value, schema);
    }

    private Result array(Schema schema, boolean complete, String seed, int depth, int variant) {
        int minItems = schema.count("minItems") == null ? 0 : schema.count("minItems");
        int maxItems = schema.count("maxItems") == null ? Integer.MAX_VALUE : schema.count("maxItems");
        if (minItems > MAX_ARRAY_ITEMS) {
            return Result.failed("minItems %d exceeds the generator limit of %d items".formatted(minItems, MAX_ARRAY_ITEMS));
        }
        int size = complete && depth < COMPLETE_DEPTH ? Math.max(minItems, 1) : minItems;
        size = Math.min(size, maxItems);
        return items(schema, size, complete, seed, depth, variant);
    }

    /**
     * An array of exactly {@code size} valid items (distinct when uniqueItems is set). The array's own
     * minItems/maxItems are deliberately not enforced: this builds values just outside them.
     */
    Result arrayOfSize(Schema schema, int size, String seed) {
        return items(schema, size, true, seed, 0, 0, false);
    }

    private Result items(Schema schema, int size, boolean complete, String seed, int depth, int variant) {
        return items(schema, size, complete, seed, depth, variant, true);
    }

    private Result items(Schema schema, int size, boolean complete, String seed, int depth, int variant,
            boolean validateArray) {
        Schema items = schema.items();
        boolean unique = Boolean.TRUE.equals(schema.merged().get("uniqueItems"));
        List<Object> value = new ArrayList<>();
        for (int index = 0; index < size; index++) {
            Result item = build(items, complete, seed + "/" + index, depth + 1, unique ? variant + index : variant);
            if (!item.ok()) {
                return Result.failed("array item: " + item.failure());
            }
            value.add(item.value());
        }
        return validateArray ? checked(value, schema) : Result.of(value);
    }

    // --- Strings ---

    private Result string(Schema schema, String seed, int variant) {
        List<String> candidates = new ArrayList<>();
        String format = schema.format();
        if (format != null) {
            String sample = formatSample(format, variant);
            if (sample != null) {
                candidates.add(sample);
            }
        }
        String pattern = schema.pattern();
        if (pattern != null) {
            candidates.addAll(patternCandidates(pattern, seed, variant));
        }
        candidates.add(variant == 0 ? "sample" : "sample" + variant);

        Integer minLength = schema.count("minLength");
        Integer maxLength = schema.count("maxLength");
        List<Object> sized = new ArrayList<>();
        for (String candidate : candidates) {
            sized.add(candidate);
            if (pattern == null) {
                sized.add(fitLength(candidate, minLength, maxLength));
            }
        }
        Result accepted = firstValid(sized, schema);
        if (accepted != null) {
            return accepted;
        }
        if (pattern != null && patterns.compile(pattern).isEmpty()) {
            return Result.failed("the pattern of %s is not a regular expression Java can evaluate".formatted(
                    schema.describe()));
        }
        return checked(sized.getLast(), schema);
    }

    private List<String> patternCandidates(String pattern, String seed, int variant) {
        List<String> candidates = new ArrayList<>();
        try {
            RgxGenProperties properties = new RgxGenProperties();
            RgxGenOption.INFINITE_PATTERN_REPETITION.setInProperties(properties, PATTERN_REPETITION);
            RgxGen generator = RgxGen.parse(properties, pattern);
            // Seeded from the value's position: the same document always gives the same strings.
            Random random = new Random(seed.hashCode() * 31L + variant);
            for (int attempt = 0; attempt < PATTERN_ATTEMPTS; attempt++) {
                candidates.add(generator.generate(random));
            }
        } catch (RuntimeException ex) {
            // RgxGen does not support every construct; the pattern is then reported as unsatisfied.
        }
        return candidates;
    }

    static String formatSample(String format, int variant) {
        int n = variant % 9;
        return switch (format) {
            case "date" -> "2024-05-1" + n;
            case "date-time" -> "2024-05-1" + n + "T10:30:00Z";
            case "time" -> "10:30:0" + n;
            case "email", "idn-email" -> (n == 0 ? "user" : "user" + n) + "@example.com";
            case "uuid" -> "3f2b8c1e-5d4a-4e6b-9c7d-1a2b3c4d5e6" + n;
            case "uri", "url", "iri" -> "https://example.com/resource" + (n == 0 ? "" : "/" + n);
            case "uri-reference", "iri-reference" -> "/resource" + (n == 0 ? "" : "/" + n);
            case "hostname", "idn-hostname" -> (n == 0 ? "" : "host" + n + ".") + "example.com";
            case "ipv4" -> "192.0.2." + (10 + n);
            case "ipv6" -> "2001:db8::" + (10 + n);
            case "byte" -> Base64.getEncoder().encodeToString(("sample" + (n == 0 ? "" : n)).getBytes(StandardCharsets.UTF_8));
            case "password" -> "Sample-Passw0rd" + (n == 0 ? "" : n);
            case "duration" -> "P" + (n + 1) + "D";
            default -> null;
        };
    }

    private static String fitLength(String value, Integer minLength, Integer maxLength) {
        String result = value;
        int length = result.codePointCount(0, result.length());
        if (minLength != null && length < minLength) {
            result = result + "x".repeat(minLength - length);
        }
        length = result.codePointCount(0, result.length());
        if (maxLength != null && length > maxLength) {
            result = result.substring(0, result.offsetByCodePoints(0, maxLength));
        }
        return result;
    }

    // --- Numbers ---

    private Result number(Schema schema, boolean integer, int variant) {
        Schema.Bound lower = schema.lower();
        Schema.Bound upper = schema.upper();
        BigDecimal step = schema.multipleOf() != null ? schema.multipleOf() : integer ? BigDecimal.ONE : null;
        BigDecimal low = lower == null ? null : smallestAllowed(lower, integer, step);
        BigDecimal high = upper == null ? null : largestAllowed(upper, integer, step);

        BigDecimal value;
        if (low != null && high != null && step == null) {
            // Strictly inside the range; each variant gets a different point.
            value = low.add(high.subtract(low).divide(BigDecimal.valueOf(variant + 2L), MathContext.DECIMAL64));
        } else {
            BigDecimal preferred = integer ? BigDecimal.ONE : new BigDecimal("1.5");
            BigDecimal base = low != null ? low : high != null ? preferred.min(high) : preferred;
            value = base.add(BigDecimal.valueOf(variant).multiply(step == null ? BigDecimal.ONE : step));
            if (step != null) {
                value = value.divide(step, 0, RoundingMode.CEILING).multiply(step);
            }
            if (high != null && value.compareTo(high) > 0) {
                value = high;
            }
            if (low != null && value.compareTo(low) < 0) {
                value = low;
            }
        }
        return checked(normalize(value), schema);
    }

    private static BigDecimal smallestAllowed(Schema.Bound bound, boolean integer, BigDecimal step) {
        if (step != null) {
            BigDecimal multiple = bound.value().divide(step, 0, RoundingMode.CEILING).multiply(step);
            return bound.exclusive() && multiple.compareTo(bound.value()) == 0 ? multiple.add(step) : multiple;
        }
        if (integer) {
            BigDecimal ceiling = bound.value().setScale(0, RoundingMode.CEILING);
            return bound.exclusive() && ceiling.compareTo(bound.value()) == 0 ? ceiling.add(BigDecimal.ONE) : ceiling;
        }
        return bound.exclusive() ? bound.value().add(new BigDecimal("0.5")) : bound.value();
    }

    private static BigDecimal largestAllowed(Schema.Bound bound, boolean integer, BigDecimal step) {
        if (step != null) {
            BigDecimal multiple = bound.value().divide(step, 0, RoundingMode.FLOOR).multiply(step);
            return bound.exclusive() && multiple.compareTo(bound.value()) == 0 ? multiple.subtract(step) : multiple;
        }
        if (integer) {
            BigDecimal floor = bound.value().setScale(0, RoundingMode.FLOOR);
            return bound.exclusive() && floor.compareTo(bound.value()) == 0 ? floor.subtract(BigDecimal.ONE) : floor;
        }
        return bound.exclusive() ? bound.value().subtract(new BigDecimal("0.5")) : bound.value();
    }

    /** Integral values become {@code Long} (or {@code BigInteger}); others a plain {@code BigDecimal}. */
    static Object normalize(BigDecimal value) {
        if (SchemaValidator.isIntegral(value)) {
            BigInteger whole = value.setScale(0, RoundingMode.UNNECESSARY).toBigInteger();
            return whole.bitLength() < 64 ? (Object) whole.longValue() : whole;
        }
        return value.stripTrailingZeros();
    }
}
