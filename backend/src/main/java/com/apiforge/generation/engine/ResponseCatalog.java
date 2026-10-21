package com.apiforge.generation.engine;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.Expectations.ResponseCheck;

/**
 * Turns an operation's documented responses into explicit expectations: which statuses count as
 * the expected outcome and which documented response bodies must match their schemas.
 */
final class ResponseCatalog {

    private final List<Map<?, ?>> responses = new ArrayList<>();

    ResponseCatalog(List<? extends Map<?, ?>> documented) {
        if (documented != null) {
            responses.addAll(documented);
        }
    }

    List<String> statuses() {
        List<String> statuses = new ArrayList<>();
        for (Map<?, ?> response : responses) {
            statuses.add(String.valueOf(response.get("status")).toUpperCase(Locale.ROOT));
        }
        return statuses;
    }

    /** The documented 2XX responses (or the 2XX class when none is documented). */
    Expectations success() {
        List<String> documented = statuses().stream().filter(status -> status.startsWith("2")).toList();
        String summary;
        List<String> statuses;
        if (documented.isEmpty()) {
            statuses = List.of("2XX");
            summary = "Succeeds with a 2XX status; the operation documents no specific success response.";
        } else {
            statuses = documented;
            summary = "Succeeds with %s, the documented success response%s.".formatted(either(documented),
                    documented.size() == 1 ? "" : "s");
        }
        return expectations(statuses, summary);
    }

    /**
     * A client error. {@code preferred} lists the statuses that fit the scenario; the documented
     * ones among them are expected, otherwise any 4XX status.
     */
    Expectations rejection(List<String> preferred) {
        List<String> statuses = documentedAmong(preferred);
        if (!statuses.isEmpty()) {
            return expectations(statuses, "Rejected with %s, the documented client-error response%s for invalid input."
                    .formatted(either(statuses), statuses.size() == 1 ? "" : "s"));
        }
        return expectations(List.of("4XX"), "Rejected with a client error (4XX). The operation does not document %s "
                .formatted(either(preferred)) + "for this case, so any 4XX status is accepted.");
    }

    /** Missing or invalid credentials: 401 or 403, documented or not (both are defined by HTTP). */
    Expectations authenticationFailure() {
        List<String> documented = documentedAmong(List.of("401", "403"));
        if (!documented.isEmpty()) {
            return expectations(documented, "Rejected with %s, the documented response%s for unauthenticated requests."
                    .formatted(either(documented), documented.size() == 1 ? "" : "s"));
        }
        return expectations(List.of("401", "403"), "Rejected with 401 or 403. The operation documents neither, "
                + "but HTTP defines them for requests without valid credentials.");
    }

    private List<String> documentedAmong(List<String> preferred) {
        Set<String> documented = new LinkedHashSet<>(statuses());
        return preferred.stream().filter(documented::contains).toList();
    }

    private Expectations expectations(List<String> statuses, String summary) {
        List<ResponseCheck> checks = new ArrayList<>();
        for (String status : statuses) {
            Map<?, ?> response = find(status);
            if (response == null || !(response.get("content") instanceof Map<?, ?> content)) {
                continue;
            }
            content.forEach((mediaType, media) -> {
                if (MediaTypes.isJson(String.valueOf(mediaType)) && media instanceof Map<?, ?> mediaMap
                        && mediaMap.get("schema") != null) {
                    checks.add(new ResponseCheck(status, String.valueOf(mediaType), schemaName(mediaMap.get("schema"))));
                }
            });
        }
        String full = summary;
        if (!checks.isEmpty()) {
            List<String> described = checks.stream().map(ResponseCatalog::describe).distinct().toList();
            full += " The response body must match " + String.join("; ", described) + ".";
        }
        return new Expectations(statuses, full, !checks.isEmpty(), checks);
    }

    /** The documented response for {@code status}: exact code, then its class, then "default". */
    private Map<?, ?> find(String status) {
        String statusClass = status.charAt(0) + "XX";
        for (String candidate : List.of(status, statusClass, "DEFAULT")) {
            for (Map<?, ?> response : responses) {
                if (String.valueOf(response.get("status")).toUpperCase(Locale.ROOT).equals(candidate)) {
                    return response;
                }
            }
        }
        return null;
    }

    private static String describe(ResponseCheck check) {
        String schema = check.schemaName() == null ? "the documented inline schema" : "schema " + check.schemaName();
        return "%s (%s, %s)".formatted(schema, check.status(), check.mediaType());
    }

    /** "Pet", "array of Pet", or {@code null} for an inline schema. */
    static String schemaName(Object schema) {
        if (schema instanceof Map<?, ?> map) {
            if (map.get("$ref") instanceof String ref && ref.startsWith(SchemaResolver.COMPONENT_PREFIX)) {
                return Pointers.unescape(ref.substring(SchemaResolver.COMPONENT_PREFIX.length()));
            }
            if ("array".equals(map.get("type")) && schemaName(map.get("items")) instanceof String items) {
                return "array of " + items;
            }
        }
        return null;
    }

    static String either(List<String> statuses) {
        if (statuses.size() == 1) {
            return statuses.getFirst();
        }
        return String.join(", ", statuses.subList(0, statuses.size() - 1)) + " or " + statuses.getLast();
    }
}
