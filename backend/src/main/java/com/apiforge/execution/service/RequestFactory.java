package com.apiforge.execution.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.apiforge.common.http.HeaderRules;
import com.apiforge.common.json.JsonValues;
import com.apiforge.environment.domain.Environment.AuthType;
import com.apiforge.environment.service.EnvironmentViews.ExecutionEnvironment;
import com.apiforge.outbound.OutboundRequest;
import com.apiforge.outbound.OutboundRequest.Header;
import com.apiforge.testcase.domain.RequestTemplate;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.BodyMode;

import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a declarative request (from a test or the console) and an environment into an outbound
 * request: base URL + path with encoded parameters, query string, default and test headers,
 * authentication, body and correlation id, with {@code {{variable}}} references resolved. Also
 * produces the {@link Redactor} for this request and the redacted snapshot that is stored.
 */
final class RequestFactory {

    /** A request ready to send, with its redactor and the redacted form that may be stored. */
    record Prepared(OutboundRequest request, Redactor redactor, Map<String, Object> snapshot) {
    }

    /** A {@code {{name}}} reference with no value in the environment. */
    static final class UnresolvedVariableException extends Exception {

        private static final long serialVersionUID = 1L;

        UnresolvedVariableException(String message) {
            super(message);
        }
    }

    static final String INVALID_TOKEN = "invalid-apiforge-credentials";
    static final int MAX_SNAPSHOT_BODY = 64 * 1024;
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]{0,63})\\s*}}");
    private static final Pattern PATH_PARAMETER = Pattern.compile("\\{([^}/]+)}");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ExecutionEnvironment environment;
    private final String correlationHeader;

    RequestFactory(ExecutionEnvironment environment, String correlationHeader) {
        this.environment = environment;
        this.correlationHeader = correlationHeader;
    }

    Prepared prepare(String method, String pathTemplate, RequestTemplate template, String correlationId)
            throws UnresolvedVariableException {
        Set<String> secrets = new LinkedHashSet<>(environment.secretVariables().values());
        Set<String> sensitiveHeaders = new LinkedHashSet<>();
        Set<String> sensitiveQuery = new LinkedHashSet<>();

        StringBuilder url = new StringBuilder(environment.baseUrl());
        url.append(expandPath(pathTemplate, template.pathParams()));

        List<String[]> query = new ArrayList<>();
        for (Map.Entry<String, Object> entry : template.query().entrySet()) {
            if (entry.getValue() instanceof List<?> values) {
                for (Object value : values) {
                    query.add(new String[] {entry.getKey(), resolve(scalar(value))});
                }
            } else {
                query.add(new String[] {entry.getKey(), resolve(scalar(entry.getValue()))});
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> header : environment.defaultHeaders().entrySet()) {
            put(headers, header.getKey(), resolve(header.getValue()));
        }
        for (Map.Entry<String, String> header : template.headers().entrySet()) {
            put(headers, header.getKey(), resolve(header.getValue()));
        }

        applyAuthentication(template.auth(), headers, query, secrets, sensitiveHeaders, sensitiveQuery);
        put(headers, correlationHeader, correlationId);

        if (!query.isEmpty()) {
            url.append('?');
            for (int index = 0; index < query.size(); index++) {
                url.append(index == 0 ? "" : "&").append(encode(query.get(index)[0])).append('=')
                        .append(encode(query.get(index)[1]));
            }
        }

        byte[] body = null;
        String contentType = null;
        Object snapshotBody = null;
        RequestTemplate.Body templateBody = template.body();
        if (templateBody.mode() == BodyMode.JSON) {
            Object resolved = resolveJson(templateBody.value());
            body = JSON.writeValueAsBytes(resolved);
            contentType = templateBody.contentType();
            snapshotBody = resolved;
        } else if (templateBody.mode() == BodyMode.RAW) {
            String text = resolve(String.valueOf(templateBody.value()));
            body = text.getBytes(StandardCharsets.UTF_8);
            contentType = templateBody.contentType();
            snapshotBody = text;
        }

        List<Header> headerList = new ArrayList<>();
        headers.forEach((name, value) -> headerList.add(new Header(name, value)));
        Redactor redactor = new Redactor(secrets, sensitiveHeaders, sensitiveQuery);
        OutboundRequest request = new OutboundRequest(method, url.toString(), headerList, body, contentType,
                Duration.ofMillis(environment.timeoutMs()), environment.followRedirects(), redactor.sensitiveHeaders());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", method);
        snapshot.put("url", redactor.url(url.toString()));
        snapshot.put("headers", redactor.headers(headerList));
        Map<String, Object> bodySnapshot = new LinkedHashMap<>();
        bodySnapshot.put("mode", templateBody.mode().name());
        if (snapshotBody != null) {
            bodySnapshot.put("contentType", contentType);
            Object redacted = redactor.json(snapshotBody);
            String serialized = redacted instanceof String text ? text : JSON.writeValueAsString(redacted);
            if (serialized.length() > MAX_SNAPSHOT_BODY) {
                bodySnapshot.put("value", serialized.substring(0, MAX_SNAPSHOT_BODY));
                bodySnapshot.put("truncated", true);
            } else {
                bodySnapshot.put("value", redacted);
            }
        }
        snapshot.put("body", bodySnapshot);
        snapshot.put("auth", template.auth().name());
        snapshot.put("correlationId", correlationId);
        return new Prepared(request, redactor, snapshot);
    }

    private void applyAuthentication(AuthMode mode, Map<String, String> headers, List<String[]> query,
            Set<String> secrets, Set<String> sensitiveHeaders, Set<String> sensitiveQuery)
            throws UnresolvedVariableException {
        AuthType type = environment.authType();
        Map<String, Object> config = environment.authConfig();
        if (type == AuthType.API_KEY_HEADER) {
            sensitiveHeaders.add(String.valueOf(config.get("headerName")).toLowerCase(Locale.ROOT));
        }
        if (type == AuthType.API_KEY_QUERY) {
            sensitiveQuery.add(String.valueOf(config.get("parameterName")));
        }
        if (mode == AuthMode.NONE) {
            return;
        }
        boolean invalid = mode == AuthMode.INVALID;
        switch (type) {
            case NONE -> {
                if (invalid) {
                    put(headers, "Authorization", "Bearer " + INVALID_TOKEN);
                }
            }
            case BEARER -> {
                String token = invalid ? INVALID_TOKEN : credential(config, "tokenVariable", secrets);
                put(headers, "Authorization", "Bearer " + token);
            }
            case BASIC -> {
                String user = invalid ? "invalid" : credential(config, "usernameVariable", secrets);
                String password = invalid ? INVALID_TOKEN : credential(config, "passwordVariable", secrets);
                String encoded = Base64.getEncoder().encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
                secrets.add(encoded);
                put(headers, "Authorization", "Basic " + encoded);
            }
            case API_KEY_HEADER -> put(headers, String.valueOf(config.get("headerName")),
                    invalid ? INVALID_TOKEN : credential(config, "valueVariable", secrets));
            case API_KEY_QUERY -> query.add(new String[] {String.valueOf(config.get("parameterName")),
                    invalid ? INVALID_TOKEN : credential(config, "valueVariable", secrets)});
        }
    }

    /** A credential's value; credentials are redacted even when kept in a plain variable. */
    private String credential(Map<String, Object> config, String key, Set<String> secrets)
            throws UnresolvedVariableException {
        String name = String.valueOf(config.get(key));
        String value = variable(name);
        if (value == null) {
            throw new UnresolvedVariableException("The authentication variable '" + name + "' is not set in environment '"
                    + environment.name() + "'.");
        }
        secrets.add(value);
        return value;
    }

    private String expandPath(String pathTemplate, Map<String, Object> pathParams) throws UnresolvedVariableException {
        Matcher matcher = PATH_PARAMETER.matcher(pathTemplate);
        StringBuilder path = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            path.append(pathTemplate, last, matcher.start());
            Object value = pathParams.get(matcher.group(1));
            if (value == null) {
                throw new UnresolvedVariableException("The path parameter '" + matcher.group(1) + "' has no value.");
            }
            // Every reserved character is encoded, so a value can never add segments, a query or a fragment.
            path.append(encode(resolve(scalar(value))));
            last = matcher.end();
        }
        path.append(pathTemplate.substring(last));
        return path.toString();
    }

    private Object resolveJson(Object value) throws UnresolvedVariableException {
        if (value instanceof String text) {
            return resolve(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                result.put(String.valueOf(entry.getKey()), resolveJson(entry.getValue()));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            for (Object element : list) {
                result.add(resolveJson(element));
            }
            return result;
        }
        return value;
    }

    /** Replaces {@code {{name}}} references with variable values. */
    String resolve(String text) throws UnresolvedVariableException {
        if (text == null || !text.contains("{{")) {
            return text;
        }
        Matcher matcher = VARIABLE.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = variable(matcher.group(1));
            if (value == null) {
                throw new UnresolvedVariableException("The variable '" + matcher.group(1)
                        + "' is not set in environment '" + environment.name() + "'.");
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String variable(String name) {
        String secret = environment.secretVariables().get(name);
        return secret != null ? secret : environment.plainVariables().get(name);
    }

    private static void put(Map<String, String> headers, String name, String value) throws UnresolvedVariableException {
        if (!HeaderRules.isValidValue(value)) {
            throw new UnresolvedVariableException("The header '" + name + "' would contain line breaks or be too long.");
        }
        headers.keySet().removeIf(existing -> existing.equalsIgnoreCase(name));
        headers.put(name, value);
    }

    private static String scalar(Object value) {
        return value instanceof Number number ? JsonValues.decimal(number).toPlainString() : String.valueOf(value);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
