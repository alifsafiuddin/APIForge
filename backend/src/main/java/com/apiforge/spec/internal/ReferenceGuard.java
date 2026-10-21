package com.apiforge.spec.internal;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.apiforge.spec.domain.ImportMessage;
import com.apiforge.spec.domain.ImportMessage.Code;
import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Checks every {@code $ref} before the OpenAPI parser runs. Only local JSON pointers
 * ({@code #/...}) that resolve inside the document are allowed. External references (URLs,
 * files, relative paths) are rejected outright, which guarantees that importing a document
 * never causes a network request or a file-system read (docs/SECURITY.md section 5).
 *
 * <p>Values that are data rather than OpenAPI structure (examples, defaults, enums, extensions)
 * are not inspected, so a literal {@code "$ref"} key inside an example is not mistaken for a
 * reference.
 */
final class ReferenceGuard {

    static final int MAX_REPORTED = 50;

    /** Keys whose values are user data. */
    private static final Set<String> DATA_KEYS = Set.of("example", "default", "enum", "const");

    /** Keys whose children are names chosen by the author, never OpenAPI keywords. */
    private static final Set<String> NAME_MAP_KEYS = Set.of("properties", "patternProperties", "$defs", "definitions",
            "schemas", "responses", "parameters", "examples", "requestBodies", "headers", "securitySchemes", "links",
            "callbacks", "pathItems", "paths", "webhooks", "content", "encoding", "variables", "mapping");

    private ReferenceGuard() {
    }

    static List<ImportMessage> check(JsonNode root) {
        List<ImportMessage> messages = new ArrayList<>();
        walk(root, root, new ArrayList<>(), null, messages);
        if (messages.size() > MAX_REPORTED) {
            int hidden = messages.size() - MAX_REPORTED;
            List<ImportMessage> truncated = new ArrayList<>(messages.subList(0, MAX_REPORTED));
            truncated.add(ImportMessage.error(Code.INVALID_REFERENCE, hidden + " further reference problems not shown.",
                    null));
            return truncated;
        }
        return messages;
    }

    private static void walk(JsonNode root, JsonNode node, List<String> path, String parentKey,
            List<ImportMessage> messages) {
        if (node.isArray()) {
            // Array items are values (parameters, allOf members, ...), never author-chosen names.
            for (int i = 0; i < node.size(); i++) {
                path.add(Integer.toString(i));
                walk(root, node.get(i), path, null, messages);
                path.removeLast();
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        boolean keysAreNames = parentKey != null && NAME_MAP_KEYS.contains(parentKey);
        JsonNode ref = keysAreNames ? null : node.get("$ref");
        if (ref != null) {
            checkReference(root, ref, Locations.format(path), messages);
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            String key = field.getKey();
            if (!keysAreNames && (DATA_KEYS.contains(key) || key.startsWith("x-") || "value".equals(key)
                    && "examples".equals(grandparentKey(path)))) {
                continue;
            }
            if (!keysAreNames && "examples".equals(key) && field.getValue().isArray()) {
                continue; // JSON Schema "examples": an array of data values
            }
            path.add(key);
            walk(root, field.getValue(), path, keysAreNames ? null : key, messages);
            path.removeLast();
        }
    }

    private static String grandparentKey(List<String> path) {
        return path.size() >= 2 ? path.get(path.size() - 2) : null;
    }

    private static void checkReference(JsonNode root, JsonNode ref, String location, List<ImportMessage> messages) {
        if (!ref.isTextual()) {
            messages.add(ImportMessage.error(Code.INVALID_REFERENCE, "'$ref' must be a string.", location));
            return;
        }
        String value = ref.textValue();
        if (!value.startsWith("#")) {
            messages.add(ImportMessage.error(Code.EXTERNAL_REFERENCE,
                    "External reference '%s' is not supported. Only references within the same document (#/...) are allowed; bundle multi-file specifications into one file before importing (for example with 'redocly bundle')."
                            .formatted(abbreviate(value)),
                    location));
            return;
        }
        if (!value.startsWith("#/")) {
            messages.add(ImportMessage.error(Code.INVALID_REFERENCE,
                    "Reference '%s' is not a JSON pointer (expected the form '#/components/schemas/Name')."
                            .formatted(abbreviate(value)),
                    location));
            return;
        }
        JsonNode target;
        try {
            String pointer = URLDecoder.decode(value.substring(1), StandardCharsets.UTF_8);
            target = root.at(JsonPointer.compile(pointer));
        } catch (IllegalArgumentException ex) {
            messages.add(ImportMessage.error(Code.INVALID_REFERENCE,
                    "Reference '%s' is not a valid JSON pointer.".formatted(abbreviate(value)), location));
            return;
        }
        if (target.isMissingNode()) {
            messages.add(ImportMessage.error(Code.UNRESOLVED_REFERENCE,
                    "Reference '%s' does not point to anything in this document.".formatted(abbreviate(value)),
                    location));
        }
    }

    private static String abbreviate(String value) {
        return value.length() > 200 ? value.substring(0, 200) + "…" : value;
    }
}
