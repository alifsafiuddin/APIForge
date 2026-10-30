package com.apiforge.testcase.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import tools.jackson.databind.json.JsonMapper;

/**
 * What a test does, independent of its name and origin: tests of one operation that send the same
 * request (path values, query, headers, body, authentication mode) and expect the same statuses
 * have the same fingerprint. Used to count duplicates once (coverage) and to keep AI suggestions
 * from repeating existing tests.
 */
public final class TestFingerprint {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TestFingerprint() {
    }

    public static String of(String operationKey, Map<String, Object> request, List<String> statuses) {
        Map<String, Object> identity = new TreeMap<>();
        identity.put("operation", operationKey);
        Map<String, Object> sent = new TreeMap<>();
        request.forEach((key, value) -> sent.put(key, ("pathParams".equals(key) || "query".equals(key))
                && value instanceof Map<?, ?> values ? asText(values) : value));
        identity.put("request", canonical(sent));
        identity.put("statuses", statuses.stream().map(status -> status.toUpperCase(Locale.ROOT)).sorted().distinct().toList());
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(identity).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }

    public static String of(TestCase test) {
        return of(test.getOperationKey(), test.getRequestTemplate(), test.getExpectationsValue().statuses());
    }

    /** Path and query values travel as URL text, so 1 and "1" are the same request. */
    private static Map<String, Object> asText(Map<?, ?> values) {
        Map<String, Object> text = new TreeMap<>();
        values.forEach((key, value) -> text.put(String.valueOf(key), value instanceof List<?> list
                ? list.stream().map(String::valueOf).toList() : String.valueOf(value)));
        return text;
    }

    /** Maps with sorted keys, recursively, so member order does not matter. */
    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, child) -> sorted.put(String.valueOf(key), canonical(child)));
            return sorted;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(TestFingerprint::canonical).toList();
        }
        return value;
    }
}
