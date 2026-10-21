package com.apiforge.testcase.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.http.HeaderRules;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.error.Problems.FieldErrorItem;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.RequestTemplate;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.Body;
import com.apiforge.testcase.domain.RequestTemplate.BodyMode;

import tools.jackson.databind.json.JsonMapper;

/**
 * Validates a user's edit of a test before it is stored. Tests stay declarative data: the request
 * can only carry parameters, safe headers and a bounded body; host, base URL and credentials
 * always come from the environment.
 */
public final class TestDefinitionValidator {

    /** Edited request as received from the client. */
    public record RequestInput(Map<String, Object> pathParams, Map<String, Object> query, Map<String, String> headers,
            BodyInput body, AuthMode auth) {
    }

    public record BodyInput(BodyMode mode, String contentType, Object value) {
    }

    public record ExpectationsInput(List<String> statuses, String summary, Boolean validateResponseSchema) {
    }

    static final int MAX_BODY_BYTES = 256 * 1024;
    static final int MAX_ENTRIES = 50;
    static final int MAX_VALUE_LENGTH = 4000;
    static final int MAX_LIST_ITEMS = 100;

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^}/]+)}");
    private static final Pattern MEDIA_TYPE = Pattern.compile(
            "^[A-Za-z0-9][A-Za-z0-9!#$&^_.+-]{0,126}/[A-Za-z0-9*][A-Za-z0-9!#$&^_.+*-]{0,126}(\\s*;[^\\p{Cntrl}]{0,200})?$");
    private static final Pattern STATUS = Pattern.compile("^[1-5]([0-9]{2}|XX)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final List<FieldErrorItem> errors = new ArrayList<>();

    private TestDefinitionValidator() {
    }

    public record Validated(RequestTemplate request, Expectations expectations) {
    }

    /**
     * @param operationKey     the test's operation ("GET /pets/{id}"); its path variables must all be set
     * @param existingChecks   documented response checks, kept for the statuses that remain expected
     */
    public static Validated validate(String operationKey, RequestInput request, ExpectationsInput expectations,
            List<Expectations.ResponseCheck> existingChecks) {
        TestDefinitionValidator validator = new TestDefinitionValidator();
        RequestTemplate template = validator.request(operationKey, request);
        Expectations expected = validator.expectations(expectations, existingChecks);
        if (!validator.errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "The test definition is invalid.", validator.errors);
        }
        return new Validated(template, expected);
    }

    private RequestTemplate request(String operationKey, RequestInput input) {
        if (input == null) {
            error("request", "must not be null");
            return null;
        }
        Map<String, Object> path = scalars("request.pathParams", input.pathParams(), false);
        Set<String> variables = pathVariables(operationKey);
        if (!path.keySet().equals(variables)) {
            error("request.pathParams", variables.isEmpty() ? "the operation has no path parameters"
                    : "must set exactly the path parameters " + String.join(", ", variables));
        }
        path.forEach((name, value) -> {
            if ("".equals(value)) {
                error("request.pathParams." + name, "must not be empty");
            }
        });
        Map<String, Object> query = scalars("request.query", input.query(), true);
        Map<String, String> headers = headers(input.headers());
        Body body = body(input.body());
        if (input.auth() == null) {
            error("request.auth", "must be ENVIRONMENT, NONE or INVALID");
        }
        return new RequestTemplate(path, query, headers, body == null ? Body.none() : body,
                input.auth() == null ? AuthMode.ENVIRONMENT : input.auth());
    }

    private Map<String, Object> scalars(String field, Map<String, Object> values, boolean allowLists) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (values == null) {
            return result;
        }
        if (values.size() > MAX_ENTRIES) {
            error(field, "must have at most %d entries".formatted(MAX_ENTRIES));
            return result;
        }
        values.forEach((name, value) -> {
            String entry = field + "." + name;
            if (!validName(name)) {
                error(field, "names must be 1–200 characters without control characters");
            } else if (value instanceof List<?> list) {
                if (!allowLists) {
                    error(entry, "must be a string, number or boolean");
                } else if (list.size() > MAX_LIST_ITEMS || !list.stream().allMatch(this::isScalar)) {
                    error(entry, "must be a list of at most %d strings, numbers or booleans".formatted(MAX_LIST_ITEMS));
                } else {
                    result.put(name, new ArrayList<>(list));
                }
            } else if (!isScalar(value)) {
                error(entry, allowLists ? "must be a string, number, boolean or a list of them"
                        : "must be a string, number or boolean");
            } else {
                result.put(name, value);
            }
        });
        return result;
    }

    private boolean isScalar(Object value) {
        if (value instanceof String text) {
            return text.length() <= MAX_VALUE_LENGTH && text.chars().noneMatch(Character::isISOControl);
        }
        return value instanceof Number || value instanceof Boolean;
    }

    private Map<String, String> headers(Map<String, String> headers) {
        Map<String, String> result = new LinkedHashMap<>();
        if (headers == null) {
            return result;
        }
        if (headers.size() > MAX_ENTRIES) {
            error("request.headers", "must have at most %d entries".formatted(MAX_ENTRIES));
            return result;
        }
        Set<String> seen = new HashSet<>();
        headers.forEach((name, value) -> {
            String field = "request.headers." + name;
            if (!HeaderRules.isValidName(name)) {
                error("request.headers", "header names must be HTTP tokens of 1–100 characters");
            } else if (HeaderRules.isForbidden(name)) {
                error(field, "cannot be set by a test (it comes from the HTTP client, the body or the environment)");
            } else if (!seen.add(name.toLowerCase(Locale.ROOT))) {
                error(field, "is set more than once");
            } else if (!HeaderRules.isValidValue(value)) {
                error(field, "must be at most %d characters without line breaks".formatted(MAX_VALUE_LENGTH));
            } else {
                result.put(name, value);
            }
        });
        return result;
    }

    private Body body(BodyInput input) {
        if (input == null || input.mode() == null) {
            error("request.body.mode", "must be NONE, JSON or RAW");
            return null;
        }
        if (input.mode() == BodyMode.NONE) {
            if (input.contentType() != null || input.value() != null) {
                error("request.body", "a body of mode NONE has no content type and no value");
            }
            return Body.none();
        }
        if (input.contentType() == null || !MEDIA_TYPE.matcher(input.contentType().strip()).matches()) {
            error("request.body.contentType", "must be a media type such as application/json");
            return null;
        }
        if (input.mode() == BodyMode.RAW && !(input.value() instanceof String)) {
            error("request.body.value", "must be a string for mode RAW");
            return null;
        }
        if (JSON.writeValueAsBytes(input.value()).length > MAX_BODY_BYTES) {
            error("request.body.value", "must be at most %d KB".formatted(MAX_BODY_BYTES / 1024));
            return null;
        }
        return new Body(input.mode(), input.contentType().strip(), input.value());
    }

    private Expectations expectations(ExpectationsInput input, List<Expectations.ResponseCheck> existingChecks) {
        if (input == null || input.statuses() == null || input.statuses().isEmpty()) {
            error("expectations.statuses", "must list at least one status, e.g. 200 or 4XX");
            return null;
        }
        Set<String> statuses = new LinkedHashSet<>();
        for (String status : input.statuses()) {
            String normalized = status == null ? "" : status.strip().toUpperCase(Locale.ROOT);
            if (!STATUS.matcher(normalized).matches()) {
                error("expectations.statuses", "'%s' is not a status code (100–599) or class (1XX–5XX)".formatted(status));
            }
            statuses.add(normalized);
        }
        if (statuses.size() > 10) {
            error("expectations.statuses", "must list at most 10 statuses");
        }
        String summary = input.summary() == null ? "" : input.summary().strip();
        if (summary.length() > 1000) {
            error("expectations.summary", "must be at most 1000 characters");
        }
        List<Expectations.ResponseCheck> checks = existingChecks.stream()
                .filter(check -> statuses.contains(check.status())).toList();
        return new Expectations(new ArrayList<>(statuses), summary,
                input.validateResponseSchema() == null || input.validateResponseSchema(), checks);
    }

    static Set<String> pathVariables(String operationKey) {
        Set<String> variables = new LinkedHashSet<>();
        Matcher matcher = PATH_VARIABLE.matcher(operationKey.substring(operationKey.indexOf(' ') + 1));
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
        return variables;
    }

    private static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 200 && name.chars().noneMatch(Character::isISOControl);
    }

    private void error(String field, String message) {
        errors.add(new FieldErrorItem(field, message));
    }
}
