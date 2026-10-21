package com.apiforge.testcase.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a test expects. A response passes the status check when its status equals one of
 * {@code statuses} ({@code "404"}) or falls in a listed class ({@code "4XX"}).
 *
 * @param statuses               exact codes or classes, never empty
 * @param summary                the expected outcome in words, including why
 * @param validateResponseSchema whether the response body is validated against the documented
 *                               schema of the returned status
 * @param responseChecks         the documented response bodies that apply to {@code statuses}
 */
public record Expectations(List<String> statuses, String summary, boolean validateResponseSchema,
        List<ResponseCheck> responseChecks) {

    /**
     * A documented response body. {@code schemaName} is a readable name of its schema ("Pet",
     * "array of Pet"), or {@code null} for an inline schema.
     */
    public record ResponseCheck(String status, String mediaType, String schemaName) {
    }

    public Expectations {
        statuses = List.copyOf(statuses);
        responseChecks = List.copyOf(responseChecks);
    }

    public Map<String, Object> toMap() {
        List<Map<String, Object>> checks = new ArrayList<>();
        for (ResponseCheck check : responseChecks) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("status", check.status());
            map.put("mediaType", check.mediaType());
            if (check.schemaName() != null) {
                map.put("schemaName", check.schemaName());
            }
            checks.add(map);
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("statuses", statuses);
        map.put("summary", summary);
        map.put("validateResponseSchema", validateResponseSchema);
        map.put("responseChecks", checks);
        return map;
    }

    public static Expectations fromMap(Map<?, ?> map) {
        List<String> statuses = new ArrayList<>();
        if (map.get("statuses") instanceof List<?> stored) {
            stored.forEach(status -> statuses.add(String.valueOf(status)));
        }
        List<ResponseCheck> checks = new ArrayList<>();
        if (map.get("responseChecks") instanceof List<?> stored) {
            for (Object entry : stored) {
                if (entry instanceof Map<?, ?> check) {
                    Object schemaName = check.get("schemaName");
                    checks.add(new ResponseCheck(String.valueOf(check.get("status")),
                            String.valueOf(check.get("mediaType")), schemaName == null ? null : schemaName.toString()));
                }
            }
        }
        Object summary = map.get("summary");
        return new Expectations(statuses, summary == null ? "" : summary.toString(),
                Boolean.TRUE.equals(map.get("validateResponseSchema")), checks);
    }
}
