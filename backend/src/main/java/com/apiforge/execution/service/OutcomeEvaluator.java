package com.apiforge.execution.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.apiforge.execution.domain.TestResult.Outcome;
import com.apiforge.testcase.domain.Expectations;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Decides a result from the response and the test's expectations. Each check ends PASS, FAIL or
 * NOT_EVALUATED (with the reason); any FAIL makes the result FAILED.
 *
 * <p>Checks in this phase: the status code, whether a documented JSON response really is JSON,
 * and JSON syntax. Validating the body against the documented schema is contract validation,
 * which arrives in a later phase: until then that check is reported as NOT_EVALUATED, never as a
 * pass, so a PASSED result states exactly what was verified.
 */
final class OutcomeEvaluator {

    enum CheckResult {
        PASS, FAIL, NOT_EVALUATED
    }

    record Evaluation(Outcome outcome, List<Map<String, Object>> checks) {
    }

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private OutcomeEvaluator() {
    }

    /**
     * @param body the response body as text, or {@code null} when it was empty or binary
     */
    static Evaluation evaluate(Expectations expectations, int status, String contentType, String body,
            boolean truncated) {
        List<Map<String, Object>> checks = new ArrayList<>();
        String statusText = String.valueOf(status);
        boolean statusMatches = expectations.statuses().stream().anyMatch(expected -> matches(expected, statusText));
        checks.add(check("STATUS", statusMatches ? CheckResult.PASS : CheckResult.FAIL, statusMatches
                ? "Status " + status + " is expected (" + String.join(", ", expectations.statuses()) + ")."
                : "Expected " + String.join(" or ", expectations.statuses()) + ", got " + status + "."));

        List<Expectations.ResponseCheck> documented = expectations.responseChecks().stream()
                .filter(check -> matches(check.status(), statusText)).toList();
        boolean declaredJson = contentType != null && isJson(contentType);
        boolean hasBody = body != null && !body.isEmpty();
        if (!documented.isEmpty() && hasBody) {
            boolean expectedJson = documented.stream().anyMatch(check -> isJson(check.mediaType()));
            if (expectedJson) {
                checks.add(check("CONTENT_TYPE", declaredJson ? CheckResult.PASS : CheckResult.FAIL, declaredJson
                        ? "The response is JSON, as documented."
                        : "A JSON body is documented for " + status + ", but the response Content-Type is "
                                + (contentType == null ? "missing" : "'" + contentType + "'") + "."));
            }
        }
        if (hasBody && (declaredJson || !documented.isEmpty() && documented.stream().anyMatch(c -> isJson(c.mediaType())))) {
            if (truncated) {
                checks.add(check("JSON_SYNTAX", CheckResult.NOT_EVALUATED,
                        "The body exceeded the size limit and was truncated, so it was not parsed."));
            } else {
                String error = jsonError(body);
                checks.add(check("JSON_SYNTAX", error == null ? CheckResult.PASS : CheckResult.FAIL,
                        error == null ? "The body is well-formed JSON." : "The body is not valid JSON: " + error));
            }
        }
        if (expectations.validateResponseSchema() && !documented.isEmpty()) {
            checks.add(check("RESPONSE_SCHEMA", CheckResult.NOT_EVALUATED,
                    "Validation against the documented schema arrives with contract validation (not implemented yet)."));
        }
        boolean failed = checks.stream().anyMatch(check -> CheckResult.FAIL.name().equals(check.get("result")));
        return new Evaluation(failed ? Outcome.FAILED : Outcome.PASSED, checks);
    }

    /** "404" matches "404"; "4XX" matches any 4xx status. */
    static boolean matches(String expected, String actual) {
        String normalized = expected.toUpperCase(Locale.ROOT);
        if (normalized.endsWith("XX")) {
            return actual.length() == 3 && actual.charAt(0) == normalized.charAt(0);
        }
        return normalized.equals("DEFAULT") || normalized.equals(actual);
    }

    static boolean isJson(String mediaType) {
        String essence = mediaType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return essence.equals("application/json") || essence.endsWith("+json");
    }

    private static String jsonError(String body) {
        try {
            JSON.readTree(body);
            return null;
        } catch (JacksonException ex) {
            // The location only: Jackson's message would quote the body.
            return ex.getLocation() == null ? "unexpected content"
                    : "unexpected content at line " + ex.getLocation().getLineNr() + ", column "
                            + ex.getLocation().getColumnNr();
        }
    }

    private static Map<String, Object> check(String name, CheckResult result, String message) {
        Map<String, Object> check = new LinkedHashMap<>();
        check.put("name", name);
        check.put("result", result.name());
        check.put("message", message);
        return check;
    }
}
