package com.apiforge.ai.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.apiforge.schema.SchemaResolver;

import tools.jackson.databind.json.JsonMapper;

/**
 * Makes data safe to put into a prompt. Results are already redacted when stored (secrets,
 * credential headers, API-key parameters); this is a second, independent pass that also removes
 * values under sensitive keys, bearer and basic tokens, JWTs and long opaque strings, and bounds the
 * size of everything. Prompts are never stored or logged.
 */
final class PromptSanitizer {

    static final String REDACTED = "«redacted»";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i).*(pass(word|wd)?|secret|token|api[-_]?key|apikey|authori[sz]ation|cookie|session|credential|private[-_]?key"
                    + "|access[-_]?key|signature|x-api-key).*");
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(Bearer|Basic|Digest|Token)\\s+[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+");
    private static final Pattern UUID = Pattern.compile("(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    /** Long runs without spaces that look like keys (hex, base64); UUIDs are kept. */
    private static final Pattern OPAQUE = Pattern.compile("[A-Za-z0-9+_=-]{32,}");
    /** Response headers worth showing a model; everything else (cookies, tokens, internals) is dropped. */
    private static final Set<String> SAFE_HEADERS = Set.of("content-type", "content-length", "allow", "retry-after",
            "location", "content-encoding");

    static final int MAX_STRING = 500;
    static final int MAX_ITEMS = 20;
    static final int MAX_DEPTH = 8;

    private PromptSanitizer() {
    }

    static Object value(Object value) {
        return value(value, 0);
    }

    private static Object value(Object value, int depth) {
        if (depth > MAX_DEPTH) {
            return "…";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (count++ >= MAX_ITEMS * 2) {
                    copy.put("…", (map.size() - MAX_ITEMS * 2) + " more members");
                    break;
                }
                String key = String.valueOf(entry.getKey());
                copy.put(key, SENSITIVE_KEY.matcher(key).matches() && !(entry.getValue() instanceof Map<?, ?>)
                        && !(entry.getValue() instanceof List<?>) ? REDACTED : value(entry.getValue(), depth + 1));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (int index = 0; index < list.size() && index < MAX_ITEMS; index++) {
                copy.add(value(list.get(index), depth + 1));
            }
            if (list.size() > MAX_ITEMS) {
                copy.add("… " + (list.size() - MAX_ITEMS) + " more items");
            }
            return copy;
        }
        if (value instanceof String text) {
            return text(text, MAX_STRING);
        }
        return value;
    }

    /** Free text with tokens removed and the length bounded. */
    static String text(String text, int max) {
        if (text == null) {
            return null;
        }
        String clean = AUTH_SCHEME.matcher(text).replaceAll("$1 " + REDACTED);
        clean = JWT.matcher(clean).replaceAll(REDACTED);
        clean = OPAQUE.matcher(clean).replaceAll(match -> UUID.matcher(match.group()).find()
                ? java.util.regex.Matcher.quoteReplacement(match.group()) : REDACTED);
        return clean.length() > max ? clean.substring(0, max) + "… (" + (clean.length() - max) + " more characters)" : clean;
    }

    /** A body excerpt: JSON is sanitized structurally, anything else as text. */
    static String body(String body, String contentType, int max) {
        if (body == null || body.isEmpty()) {
            return body;
        }
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).contains("json")) {
            try {
                String json = JSON.writeValueAsString(value(JSON.readValue(body, Object.class)));
                return json.length() > max ? json.substring(0, max) + "… (truncated)" : json;
            } catch (RuntimeException ignored) {
                // Not valid JSON: fall through to text.
            }
        }
        return text(body, max);
    }

    /** Only harmless response headers, with values sanitized. */
    static Map<String, Object> headers(Map<String, Object> headers) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> {
                if (SAFE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    safe.put(name, text(String.valueOf(value), 200));
                }
            });
        }
        return safe;
    }

    /** Request headers the test chose, without anything credential-like or already redacted. */
    static Map<String, Object> requestHeaders(Map<?, ?> headers, Set<String> drop) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (headers != null) {
            headers.forEach((name, value) -> {
                String key = String.valueOf(name);
                String text = String.valueOf(value);
                if (!SENSITIVE_KEY.matcher(key).matches() && !drop.contains(key.toLowerCase(Locale.ROOT))
                        && !text.contains(REDACTED)) {
                    safe.put(key, text(text, 200));
                }
            });
        }
        return safe;
    }

    /**
     * A schema with component references inlined up to {@code depth} levels (deeper ones stay as
     * {@code $ref} with the component name), examples sanitized, and the result bounded.
     */
    static Object schema(Object node, Map<String, Object> components, int depth) {
        return value(inline(node, components, depth));
    }

    private static Object inline(Object node, Map<String, Object> components, int depth) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String ref && ref.startsWith(SchemaResolver.COMPONENT_PREFIX)) {
                String name = ref.substring(SchemaResolver.COMPONENT_PREFIX.length());
                Object target = components.get(name);
                if (depth <= 0 || target == null) {
                    return Map.of("$ref", name);
                }
                Map<String, Object> resolved = new LinkedHashMap<>();
                resolved.put("title", name);
                if (inline(target, components, depth - 1) instanceof Map<?, ?> inlined) {
                    inlined.forEach((key, value) -> resolved.put(String.valueOf(key), value));
                }
                return resolved;
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, value) -> copy.put(String.valueOf(key), inline(value, components, depth)));
            return copy;
        }
        if (node instanceof List<?> list) {
            return list.stream().map(item -> inline(item, components, depth)).toList();
        }
        return node;
    }
}
