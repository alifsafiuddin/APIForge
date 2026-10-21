package com.apiforge.schema;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.apiforge.common.json.JsonValues;

/**
 * A resolved schema. {@code raw} is the node as written (after following {@code $ref}), used for
 * validation; {@code merged} additionally has allOf flattened, used to synthesize values.
 * Understands both OpenAPI 3.0 ({@code nullable}, boolean {@code exclusiveMinimum}) and 3.1
 * (type arrays, numeric {@code exclusiveMinimum}, {@code examples}, {@code const}).
 */
public final class Schema {

    /** Marker stored in {@code merged} when allOf members cannot all hold at once. */
    public static final String PROBLEM_KEY = "x-apiforge-problem";

    /** An inclusive or exclusive numeric bound. */
    public record Bound(BigDecimal value, boolean exclusive) {
    }

    private final SchemaResolver resolver;
    private final Map<String, Object> raw;
    private final Map<String, Object> merged;
    private final String location;
    private final String name;
    private final String problem;
    private final boolean never;

    Schema(SchemaResolver resolver, Map<String, Object> raw, Map<String, Object> merged, String location, String name,
            String problem) {
        this(resolver, raw, merged, location, name, problem, false);
    }

    private Schema(SchemaResolver resolver, Map<String, Object> raw, Map<String, Object> merged, String location,
            String name, String problem, boolean never) {
        this.resolver = resolver;
        this.raw = raw;
        this.merged = merged;
        this.location = location;
        this.name = name;
        this.problem = problem != null ? problem
                : merged.get(PROBLEM_KEY) instanceof String conflict ? conflict : null;
        this.never = never;
    }

    public static Schema any(SchemaResolver resolver, String location) {
        return new Schema(resolver, Map.of(), Map.of(), location, null, null, false);
    }

    public static Schema never(SchemaResolver resolver, String location) {
        return new Schema(resolver, Map.of(), Map.of(), location, null, null, true);
    }

    public static Schema unresolvable(SchemaResolver resolver, String location, String problem) {
        return new Schema(resolver, Map.of(), Map.of(), location, null, problem, false);
    }

    public SchemaResolver resolver() {
        return resolver;
    }

    /** This node's own keywords only (allOf members not merged); used by the validator. */
    public Schema own() {
        Map<String, Object> own = new LinkedHashMap<>(raw);
        own.remove("allOf");
        return new Schema(resolver, raw, own, location, name, problem, never);
    }

    public Map<String, Object> raw() {
        return raw;
    }

    public Map<String, Object> merged() {
        return merged;
    }

    /** Specification location (JSON pointer) for explanations. */
    public String location() {
        return location;
    }

    /** Component name when reached through a reference, else {@code null}. */
    public String name() {
        return name;
    }

    /** Why the schema cannot be used, or {@code null}. */
    public String problem() {
        return problem;
    }

    public boolean isNever() {
        return never;
    }

    /** "schema Pet" or "the schema at #/paths/...". */
    public String describe() {
        return name != null ? "schema " + name : "the schema at " + location;
    }

    // --- Types ---

    /** Declared types, including "null" for 3.0 {@code nullable: true}; empty means any type. */
    public Set<String> declaredTypes() {
        Set<String> types = new LinkedHashSet<>(SchemaResolver.typeList(merged.get("type")));
        if (Boolean.TRUE.equals(merged.get("nullable")) && !types.isEmpty()) {
            types.add("null");
        }
        return types;
    }

    public boolean allowsNull() {
        Set<String> types = declaredTypes();
        return types.isEmpty() || types.contains("null");
    }

    /** The type to synthesize: the first declared non-null type, else one inferred from the keywords. */
    public String primaryType() {
        for (String type : declaredTypes()) {
            if (!"null".equals(type)) {
                return type;
            }
        }
        if (has("properties") || has("required") || has("additionalProperties") || has("minProperties")) {
            return "object";
        }
        if (has("items") || has("minItems") || has("maxItems")) {
            return "array";
        }
        if (has("minLength") || has("maxLength") || has("pattern") || has("format")) {
            return "string";
        }
        if (has("minimum") || has("maximum") || has("multipleOf")) {
            return "number";
        }
        if (enumValues() != null && !enumValues().isEmpty()) {
            return typeOfValue(enumValues().getFirst());
        }
        if (has("const")) {
            return typeOfValue(merged.get("const"));
        }
        return declaredTypes().contains("null") ? "null" : null;
    }

    private static String typeOfValue(Object value) {
        String type = JsonValues.typeName(value);
        if ("number".equals(type) && JsonValues.decimal((Number) value).stripTrailingZeros().scale() <= 0) {
            return "integer";
        }
        return type;
    }

    public boolean has(String keyword) {
        return merged.containsKey(keyword);
    }

    // --- Values ---

    public List<?> enumValues() {
        return merged.get("enum") instanceof List<?> values ? values : null;
    }

    public Object constValue() {
        return merged.get("const");
    }

    /** Documented example values: {@code example} (3.0) and the {@code examples} array (3.1). */
    public List<Object> examples() {
        List<Object> examples = new ArrayList<>();
        if (merged.containsKey("example")) {
            examples.add(merged.get("example"));
        }
        if (merged.get("examples") instanceof List<?> list) {
            examples.addAll(list);
        }
        return examples;
    }

    public String format() {
        return merged.get("format") instanceof String format ? format : null;
    }

    public String pattern() {
        return merged.get("pattern") instanceof String pattern ? pattern : null;
    }

    public boolean readOnly() {
        return Boolean.TRUE.equals(merged.get("readOnly"));
    }

    public boolean writeOnly() {
        return Boolean.TRUE.equals(merged.get("writeOnly"));
    }

    // --- Numeric constraints ---

    public Bound lower() {
        return bound("minimum", "exclusiveMinimum", true);
    }

    public Bound upper() {
        return bound("maximum", "exclusiveMaximum", false);
    }

    private Bound bound(String inclusiveKey, String exclusiveKey, boolean lower) {
        BigDecimal inclusive = decimal(inclusiveKey);
        Object exclusive = merged.get(exclusiveKey);
        if (exclusive instanceof Number number) {
            BigDecimal value = JsonValues.decimal(number);
            boolean tighter = inclusive == null || (lower ? value.compareTo(inclusive) >= 0 : value.compareTo(inclusive) <= 0);
            return tighter ? new Bound(value, true) : new Bound(inclusive, false);
        }
        return inclusive == null ? null : new Bound(inclusive, Boolean.TRUE.equals(exclusive));
    }

    public BigDecimal multipleOf() {
        BigDecimal value = decimal("multipleOf");
        return value != null && value.signum() > 0 ? value : null;
    }

    private BigDecimal decimal(String keyword) {
        return merged.get(keyword) instanceof Number number ? JsonValues.decimal(number) : null;
    }

    /** A non-negative integer keyword (minLength, maxItems, ...), or {@code null}. */
    public Integer count(String keyword) {
        if (merged.get(keyword) instanceof Number number) {
            BigDecimal value = JsonValues.decimal(number);
            if (value.signum() < 0) {
                return 0;
            }
            return value.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : value.intValue();
        }
        return null;
    }

    // --- Structure ---

    public Schema items() {
        return resolver.resolve(merged.get("items"), location + "/items");
    }

    public List<String> propertyNames() {
        List<String> names = new ArrayList<>();
        if (merged.get("properties") instanceof Map<?, ?> properties) {
            properties.keySet().forEach(key -> names.add(String.valueOf(key)));
        }
        return names;
    }

    public boolean declaresProperty(String property) {
        return merged.get("properties") instanceof Map<?, ?> properties && properties.containsKey(property);
    }

    public Schema property(String property) {
        Object node = merged.get("properties") instanceof Map<?, ?> properties ? properties.get(property) : null;
        if (node == null && merged.get("additionalProperties") instanceof Map<?, ?> additional) {
            return resolver.resolve(additional, location + "/additionalProperties");
        }
        return resolver.resolve(node, location + "/properties/" + Pointers.escape(property));
    }

    public List<String> required() {
        List<String> required = new ArrayList<>();
        if (merged.get("required") instanceof List<?> list) {
            list.forEach(entry -> required.add(String.valueOf(entry)));
        }
        return required;
    }

    /** {@code false} when additionalProperties is {@code false}. */
    public boolean allowsAdditionalProperties() {
        return !Boolean.FALSE.equals(merged.get("additionalProperties"));
    }

    public List<?> oneOf() {
        return merged.get("oneOf") instanceof List<?> list ? list : List.of();
    }

    public List<?> anyOf() {
        return merged.get("anyOf") instanceof List<?> list ? list : List.of();
    }

    /** discriminator.propertyName, or {@code null}. */
    public String discriminatorProperty() {
        return merged.get("discriminator") instanceof Map<?, ?> discriminator
                && discriminator.get("propertyName") instanceof String property ? property : null;
    }

    /** The discriminator value that selects the component {@code componentName}. */
    public String discriminatorValue(String componentName) {
        if (merged.get("discriminator") instanceof Map<?, ?> discriminator
                && discriminator.get("mapping") instanceof Map<?, ?> mapping) {
            for (Map.Entry<?, ?> entry : mapping.entrySet()) {
                String target = String.valueOf(entry.getValue());
                if (target.equals(componentName) || target.equals(SchemaResolver.COMPONENT_PREFIX + componentName)) {
                    return String.valueOf(entry.getKey());
                }
            }
        }
        return componentName;
    }
}
