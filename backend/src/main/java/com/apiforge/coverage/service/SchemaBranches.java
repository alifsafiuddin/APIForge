package com.apiforge.coverage.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.apiforge.schema.JsonPaths;
import com.apiforge.schema.Pointers;
import com.apiforge.schema.Schema;
import com.apiforge.schema.SchemaValidator;

/**
 * The schema branches of a request body: every alternative of a {@code oneOf} or {@code anyOf},
 * at the location where it applies. A branch is exercised by a request whose value at that
 * location validates against the alternative.
 */
public final class SchemaBranches {

    /** Composition inside composition inside ... is followed this deep; deeper branches are not listed. */
    static final int MAX_DEPTH = 6;
    static final int MAX_BRANCHES = 200;
    /** Marks "every item of the array" in a branch location. */
    static final String EACH_ITEM = "*";

    /**
     * @param id       stable within a version: location, composition keyword and alternative index
     * @param location tokens from the body root; {@value #EACH_ITEM} stands for every array item
     * @param label    e.g. "delivery: one of Address" or "delivery: one of alternative 2"
     */
    public record Branch(String id, List<String> location, String label, Schema schema) {

        /** Where the branch applies, as a JSON path ({@code $.lines[*].delivery}). */
        public String path() {
            StringBuilder path = new StringBuilder("$");
            for (String token : location) {
                path.append(EACH_ITEM.equals(token) ? "[*]" : JsonPaths.fromPointer("/" + Pointers.escape(token)).substring(1));
            }
            return path.toString();
        }
    }

    private SchemaBranches() {
    }

    public static List<Branch> find(Schema body) {
        List<Branch> branches = new ArrayList<>();
        walk(body, new ArrayList<>(), 0, new HashSet<>(), branches);
        return List.copyOf(branches);
    }

    private static void walk(Schema schema, List<String> location, int depth, Set<String> visiting, List<Branch> out) {
        if (schema == null || schema.problem() != null || schema.isNever() || depth > MAX_DEPTH || out.size() >= MAX_BRANCHES) {
            return;
        }
        // A schema that contains itself (directly or through others) is not entered again: its
        // branches were listed where it first appears.
        String visit = schema.location();
        if (!visiting.add(visit)) {
            return;
        }
        for (String keyword : List.of("oneOf", "anyOf")) {
            List<?> alternatives = "oneOf".equals(keyword) ? schema.oneOf() : schema.anyOf();
            for (int index = 0; index < alternatives.size() && out.size() < MAX_BRANCHES; index++) {
                Schema alternative = schema.resolver().alternative(schema, alternatives.get(index),
                        schema.location() + "/" + keyword + "/" + index);
                String name = alternative.name() != null ? alternative.name() : "alternative " + (index + 1);
                String where = location.isEmpty() ? "body" : location.getLast().equals(EACH_ITEM)
                        ? fieldName(location) + " item" : fieldName(location);
                out.add(new Branch(String.join("/", location) + "#" + keyword + "/" + index, List.copyOf(location),
                        where + ": " + ("oneOf".equals(keyword) ? "one of " : "any of ") + name, alternative));
                walk(alternative, location, depth + 1, visiting, out);
            }
        }
        for (String property : schema.propertyNames()) {
            List<String> child = new ArrayList<>(location);
            child.add(property);
            walk(schema.property(property), child, depth + 1, visiting, out);
        }
        if (schema.declaredTypes().contains("array") || schema.raw().containsKey("items")) {
            List<String> child = new ArrayList<>(location);
            child.add(EACH_ITEM);
            walk(schema.items(), child, depth + 1, visiting, out);
        }
        visiting.remove(visit);
    }

    private static String fieldName(List<String> location) {
        for (int index = location.size() - 1; index >= 0; index--) {
            if (!EACH_ITEM.equals(location.get(index))) {
                return location.get(index);
            }
        }
        return "body";
    }

    /** The ids of {@code branches} that {@code body} exercises. */
    public static Set<String> exercised(Object body, List<Branch> branches, SchemaValidator validator) {
        Set<String> hit = new LinkedHashSet<>();
        for (Branch branch : branches) {
            for (Object value : valuesAt(body, branch.location(), 0)) {
                SchemaValidator.Result result = validator.validate(value, branch.schema());
                if (result.valid() && result.unevaluated().isEmpty()) {
                    hit.add(branch.id());
                    break;
                }
            }
        }
        return hit;
    }

    private static List<Object> valuesAt(Object value, List<String> location, int index) {
        if (index == location.size()) {
            return value == null ? List.of() : List.of(value);
        }
        String token = location.get(index);
        if (EACH_ITEM.equals(token)) {
            if (!(value instanceof List<?> items)) {
                return List.of();
            }
            List<Object> values = new ArrayList<>();
            items.forEach(item -> values.addAll(valuesAt(item, location, index + 1)));
            return values;
        }
        if (value instanceof Map<?, ?> object && object.containsKey(token)) {
            return valuesAt(object.get(token), location, index + 1);
        }
        return List.of();
    }
}
