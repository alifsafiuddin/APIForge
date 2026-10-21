package com.apiforge.generation.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.apiforge.common.json.JsonValues;

/**
 * Resolves schema nodes stored with an operation against the version's component schemas:
 * follows {@code $ref} chains (local {@code #/components/schemas/...} only, as guaranteed by the
 * import) and prepares a flattened view of {@code allOf} for value synthesis. Validation always
 * uses the original node, so flattening never weakens a check.
 */
final class SchemaResolver {

    static final String COMPONENT_PREFIX = "#/components/schemas/";
    private static final int MAX_HOPS = 20;
    private static final int MAX_ALL_OF_DEPTH = 10;

    private final Map<?, ?> components;

    SchemaResolver(Map<?, ?> components) {
        this.components = components == null ? Map.of() : components;
    }

    /** @param location where {@code node} sits in the specification, used in explanations */
    Schema resolve(Object node, String location) {
        return resolve(node, location, 0);
    }

    private Schema resolve(Object node, String location, int allOfDepth) {
        if (node == null) {
            return Schema.any(this, location);
        }
        Object current = node;
        String currentLocation = location;
        String name = null;
        Map<String, Object> siblings = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (int hop = 0; current instanceof Map<?, ?> map && map.get("$ref") instanceof String ref; hop++) {
            if (hop >= MAX_HOPS || !ref.startsWith(COMPONENT_PREFIX)) {
                return Schema.unresolvable(this, currentLocation, "the reference " + ref + " cannot be resolved");
            }
            String target = Pointers.unescape(ref.substring(COMPONENT_PREFIX.length()));
            if (!seen.add(target) || !components.containsKey(target)) {
                return Schema.unresolvable(this, currentLocation, "the reference " + ref + " cannot be resolved");
            }
            // OpenAPI 3.1 allows keywords next to $ref (e.g. readOnly); they apply in addition to the target.
            map.forEach((key, value) -> {
                if (!"$ref".equals(key)) {
                    siblings.putIfAbsent(String.valueOf(key), value);
                }
            });
            current = components.get(target);
            name = target;
            currentLocation = COMPONENT_PREFIX + Pointers.escape(target);
        }
        if (current instanceof Boolean allowed) {
            return allowed ? Schema.any(this, currentLocation) : Schema.never(this, currentLocation);
        }
        if (!(current instanceof Map<?, ?> map)) {
            return Schema.any(this, currentLocation);
        }
        Map<String, Object> raw = JsonValues.copyObject(map);
        if (!siblings.isEmpty()) {
            // Validation sees the siblings as an extra allOf member of the target.
            Map<String, Object> combined = new LinkedHashMap<>(siblings);
            combined.put("allOf", List.of(raw));
            raw = combined;
        }
        if (allOfDepth > MAX_ALL_OF_DEPTH) {
            return Schema.unresolvable(this, currentLocation, "allOf is nested more than " + MAX_ALL_OF_DEPTH + " levels deep");
        }
        Map<String, Object> merged = flatten(raw, currentLocation, allOfDepth);
        return new Schema(this, raw, merged, currentLocation, name, null);
    }

    /** {@code raw} with every allOf member merged in (properties united, bounds tightened). */
    private Map<String, Object> flatten(Map<String, Object> raw, String location, int allOfDepth) {
        Map<String, Object> merged = new LinkedHashMap<>(raw);
        merged.remove("allOf");
        if (raw.get("allOf") instanceof List<?> members) {
            for (int index = 0; index < members.size(); index++) {
                Schema member = resolve(members.get(index), location + "/allOf/" + index, allOfDepth + 1);
                if (member.problem() != null) {
                    merged.put(Schema.PROBLEM_KEY, member.problem());
                } else if (member.isNever()) {
                    merged.put(Schema.PROBLEM_KEY, "an allOf member accepts no value");
                } else {
                    mergeInto(merged, member.merged());
                }
            }
        }
        return merged;
    }

    /**
     * A oneOf/anyOf alternative as a standalone schema: the parent's own keywords (without the
     * composition) merged with the alternative.
     */
    Schema alternative(Schema parent, Object alternativeNode, String location) {
        Schema alternative = resolve(alternativeNode, location);
        if (alternative.problem() != null || alternative.isNever()) {
            return alternative;
        }
        Map<String, Object> merged = new LinkedHashMap<>(parent.merged());
        merged.remove("oneOf");
        merged.remove("anyOf");
        merged.remove("discriminator");
        mergeInto(merged, alternative.merged());
        Map<String, Object> raw = new LinkedHashMap<>(merged);
        raw.remove(Schema.PROBLEM_KEY);
        return new Schema(this, raw, merged, alternative.location(), alternative.name(), null);
    }

    private static void mergeInto(Map<String, Object> target, Map<String, Object> source) {
        source.forEach((key, value) -> {
            Object existing = target.get(key);
            if (existing == null) {
                target.put(key, value);
                return;
            }
            switch (key) {
                case "properties" -> {
                    Map<String, Object> properties = JsonValues.copyObject(existing);
                    JsonValues.copyObject(value).forEach((name, schema) -> properties.merge(name, schema,
                            (first, second) -> Map.of("allOf", List.of(first, second))));
                    target.put(key, properties);
                }
                case "required" -> {
                    Set<Object> union = new LinkedHashSet<>();
                    if (existing instanceof List<?> list) {
                        union.addAll(list);
                    }
                    if (value instanceof List<?> list) {
                        union.addAll(list);
                    }
                    target.put(key, new ArrayList<>(union));
                }
                case "minimum", "minLength", "minItems", "minProperties" -> target.put(key, max(existing, value));
                case "maximum", "maxLength", "maxItems", "maxProperties" -> target.put(key, min(existing, value));
                case "enum" -> {
                    List<Object> both = new ArrayList<>();
                    if (existing instanceof List<?> first && value instanceof List<?> second) {
                        for (Object candidate : first) {
                            if (second.stream().anyMatch(other -> JsonValues.jsonEquals(candidate, other))) {
                                both.add(candidate);
                            }
                        }
                    }
                    target.put(key, both);
                }
                case "type" -> {
                    Set<String> common = new LinkedHashSet<>(typeList(existing));
                    common.retainAll(typeList(value));
                    if (common.isEmpty()) {
                        target.put(Schema.PROBLEM_KEY, "allOf members declare incompatible types");
                    } else {
                        target.put(key, common.size() == 1 ? common.iterator().next() : new ArrayList<>(common));
                    }
                }
                default -> {
                    // Annotations and other keywords: the first declaration wins.
                }
            }
        });
    }

    static List<String> typeList(Object type) {
        List<String> types = new ArrayList<>();
        if (type instanceof String single) {
            types.add(single);
        } else if (type instanceof List<?> list) {
            list.forEach(entry -> types.add(String.valueOf(entry)));
        }
        return types;
    }

    private static Object max(Object first, Object second) {
        if (first instanceof Number a && second instanceof Number b) {
            return JsonValues.decimal(a).compareTo(JsonValues.decimal(b)) >= 0 ? a : b;
        }
        return first;
    }

    private static Object min(Object first, Object second) {
        if (first instanceof Number a && second instanceof Number b) {
            BigDecimal left = JsonValues.decimal(a);
            return left.compareTo(JsonValues.decimal(b)) <= 0 ? a : b;
        }
        return first;
    }
}
