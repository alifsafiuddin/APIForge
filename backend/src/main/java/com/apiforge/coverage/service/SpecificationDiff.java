package com.apiforge.coverage.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import com.apiforge.coverage.service.CoverageViews.ChangeKind;
import com.apiforge.schema.SchemaResolver;

/**
 * Which operations were added, removed or changed between two specification versions. An
 * operation changed when its parameters, request body, responses or security differ, or when a
 * component schema it references (directly or through other schemas) differs.
 */
public final class SpecificationDiff {

    public static final String PARAMETERS = "parameters";
    public static final String REQUEST_BODY = "request body";
    public static final String RESPONSES = "responses";
    public static final String SECURITY = "security";
    public static final String REFERENCED_SCHEMAS = "referenced schemas";

    /** One operation's contract as stored at import. */
    public record Contract(String operationKey, List<Map<String, Object>> parameters, Map<String, Object> requestBody,
            List<Map<String, Object>> responses, List<Map<String, Object>> security) {
    }

    public record Change(String operationKey, ChangeKind kind, List<String> aspects) {
    }

    public record Result(List<Change> changes, int unchanged) {
    }

    private SpecificationDiff() {
    }

    public static Result diff(Map<String, Object> fromComponents, List<Contract> from, Map<String, Object> toComponents,
            List<Contract> to) {
        Map<String, Contract> before = new LinkedHashMap<>();
        from.forEach(contract -> before.put(contract.operationKey(), contract));
        List<Change> changes = new ArrayList<>();
        int unchanged = 0;
        for (Contract after : to) {
            Contract previous = before.remove(after.operationKey());
            if (previous == null) {
                changes.add(new Change(after.operationKey(), ChangeKind.ADDED, List.of()));
                continue;
            }
            List<String> aspects = new ArrayList<>();
            if (!Objects.equals(sorted(previous.parameters(), "in", "name"), sorted(after.parameters(), "in", "name"))) {
                aspects.add(PARAMETERS);
            }
            if (!Objects.equals(previous.requestBody(), after.requestBody())) {
                aspects.add(REQUEST_BODY);
            }
            if (!Objects.equals(sorted(previous.responses(), "status"), sorted(after.responses(), "status"))) {
                aspects.add(RESPONSES);
            }
            if (!Objects.equals(previous.security(), after.security())) {
                aspects.add(SECURITY);
            }
            if (aspects.isEmpty() && referencedSchemasDiffer(previous, fromComponents, after, toComponents)) {
                aspects.add(REFERENCED_SCHEMAS);
            }
            if (aspects.isEmpty()) {
                unchanged++;
            } else {
                changes.add(new Change(after.operationKey(), ChangeKind.CHANGED, List.copyOf(aspects)));
            }
        }
        before.keySet().forEach(key -> changes.add(new Change(key, ChangeKind.REMOVED, List.of())));
        changes.sort(Comparator.comparing((Change change) -> change.kind().ordinal()).thenComparing(Change::operationKey));
        return new Result(List.copyOf(changes), unchanged);
    }

    private static List<Map<String, Object>> sorted(List<Map<String, Object>> entries, String... keys) {
        if (entries == null) {
            return List.of();
        }
        List<Map<String, Object>> copy = new ArrayList<>(entries);
        Comparator<Map<String, Object>> order = Comparator.comparing(entry -> String.valueOf(entry.get(keys[0])));
        for (int index = 1; index < keys.length; index++) {
            String key = keys[index];
            order = order.thenComparing(entry -> String.valueOf(entry.get(key)));
        }
        copy.sort(order);
        return copy;
    }

    private static boolean referencedSchemasDiffer(Contract previous, Map<String, Object> fromComponents, Contract after,
            Map<String, Object> toComponents) {
        Set<String> names = new TreeSet<>(referenced(previous, fromComponents));
        names.addAll(referenced(after, toComponents));
        for (String name : names) {
            if (!Objects.equals(fromComponents.get(name), toComponents.get(name))) {
                return true;
            }
        }
        return false;
    }

    /** Names of the component schemas reachable from an operation's contract. */
    static Set<String> referenced(Contract contract, Map<String, Object> components) {
        Set<String> names = new LinkedHashSet<>();
        Deque<Object> pending = new ArrayDeque<>();
        pending.add(contract.parameters() == null ? List.of() : contract.parameters());
        if (contract.requestBody() != null) {
            pending.add(contract.requestBody());
        }
        pending.add(contract.responses() == null ? List.of() : contract.responses());
        while (!pending.isEmpty()) {
            Object node = pending.pop();
            if (node instanceof Map<?, ?> map) {
                if (map.get("$ref") instanceof String ref && ref.startsWith(SchemaResolver.COMPONENT_PREFIX)) {
                    String name = ref.substring(SchemaResolver.COMPONENT_PREFIX.length());
                    if (names.add(name) && components.get(name) != null) {
                        pending.add(components.get(name));
                    }
                }
                map.values().forEach(value -> {
                    if (value != null) {
                        pending.add(value);
                    }
                });
            } else if (node instanceof List<?> list) {
                list.forEach(value -> {
                    if (value != null) {
                        pending.add(value);
                    }
                });
            }
        }
        return names;
    }
}
