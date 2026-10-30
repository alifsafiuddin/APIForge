package com.apiforge.validation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One checked statement about a response.
 *
 * @param check    what was checked: STATUS, STATUS_DOCUMENTED, CONTENT_TYPE, JSON_SYNTAX, BODY_SCHEMA
 * @param path     JSON path of the offending value ({@code $.items[2].price}) for body findings
 * @param keyword  the schema keyword that failed (type, required, enum, maximum, ...)
 * @param expected what the contract or the test requires, in words
 * @param actual   what the response contained
 */
public record Assertion(String check, Result result, String message, String path, String keyword, String expected,
        String actual) {

    /**
     * PASS and FAIL are decisions. SKIPPED means the check does not apply (and why); INCONCLUSIVE
     * means it applies but could not be decided. Neither is ever counted as a pass.
     */
    public enum Result {
        PASS, FAIL, SKIPPED, INCONCLUSIVE
    }

    public static Assertion pass(String check, String message) {
        return new Assertion(check, Result.PASS, message, null, null, null, null);
    }

    public static Assertion fail(String check, String message, String expected, String actual) {
        return new Assertion(check, Result.FAIL, message, null, null, expected, actual);
    }

    public static Assertion skipped(String check, String message) {
        return new Assertion(check, Result.SKIPPED, message, null, null, null, null);
    }

    public static Assertion inconclusive(String check, String message) {
        return new Assertion(check, Result.INCONCLUSIVE, message, null, null, null, null);
    }

    /** Storage and API form; absent fields are left out. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", check);
        map.put("result", result.name());
        map.put("message", message);
        if (path != null) {
            map.put("path", path);
        }
        if (keyword != null) {
            map.put("keyword", keyword);
        }
        if (expected != null) {
            map.put("expected", expected);
        }
        if (actual != null) {
            map.put("actual", actual);
        }
        return map;
    }
}
