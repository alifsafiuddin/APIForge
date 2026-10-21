package com.apiforge.execution.service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.apiforge.outbound.OutboundRequest.Header;

/**
 * Removes credentials from everything that is stored, returned or logged about an execution
 * (docs/SECURITY.md section 7): known secret values in raw, URL-encoded and Base64 form, the
 * values of sensitive headers, and API-key query parameters. It runs before persisting request
 * and response snapshots, so secrets never reach the database or the API.
 */
final class Redactor {

    static final String MASK = "«redacted»";

    /** Always hidden, whatever the configuration. */
    static final Set<String> ALWAYS_SENSITIVE_HEADERS = Set.of("authorization", "proxy-authorization", "cookie",
            "set-cookie", "x-api-key", "x-auth-token", "x-csrf-token", "x-xsrf-token");

    private final List<String> needles;
    private final Set<String> sensitiveHeaders;
    private final Set<String> sensitiveQueryParameters;

    Redactor(Collection<String> secrets, Collection<String> extraSensitiveHeaders, Collection<String> sensitiveQueryParameters) {
        Set<String> forms = new LinkedHashSet<>();
        for (String secret : secrets) {
            if (secret == null || secret.isEmpty()) {
                continue;
            }
            forms.add(secret);
            forms.add(URLEncoder.encode(secret, StandardCharsets.UTF_8));
            forms.add(URLEncoder.encode(secret, StandardCharsets.UTF_8).replace("+", "%20"));
            forms.add(Base64.getEncoder().encodeToString(secret.getBytes(StandardCharsets.UTF_8)));
        }
        List<String> sorted = new ArrayList<>(forms);
        // Longest first, so a secret that contains another one is replaced as a whole.
        sorted.sort(Comparator.comparingInt(String::length).reversed());
        this.needles = List.copyOf(sorted);
        Set<String> headers = new LinkedHashSet<>(ALWAYS_SENSITIVE_HEADERS);
        extraSensitiveHeaders.forEach(name -> headers.add(name.toLowerCase(Locale.ROOT)));
        this.sensitiveHeaders = Set.copyOf(headers);
        this.sensitiveQueryParameters = Set.copyOf(sensitiveQueryParameters);
    }

    Set<String> sensitiveHeaders() {
        return sensitiveHeaders;
    }

    String text(String value) {
        if (value == null || needles.isEmpty()) {
            return value;
        }
        String result = value;
        for (String needle : needles) {
            if (result.contains(needle)) {
                result = result.replace(needle, MASK);
            }
        }
        return result;
    }

    /** Header map for storage; repeated headers become lists. */
    Map<String, Object> headers(List<Header> headers) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Header header : headers) {
            String value = sensitiveHeaders.contains(header.name().toLowerCase(Locale.ROOT)) ? MASK : text(header.value());
            result.merge(header.name(), value, (first, second) -> {
                List<Object> values = new ArrayList<>();
                if (first instanceof List<?> list) {
                    values.addAll(list);
                } else {
                    values.add(first);
                }
                values.add(second);
                return values;
            });
        }
        return result;
    }

    /** A URL with the values of sensitive query parameters (and any secret) masked. */
    String url(String url) {
        if (url == null) {
            return null;
        }
        int queryStart = url.indexOf('?');
        if (queryStart < 0 || sensitiveQueryParameters.isEmpty()) {
            return text(url);
        }
        StringBuilder result = new StringBuilder(url.substring(0, queryStart + 1));
        String[] pairs = url.substring(queryStart + 1).split("&", -1);
        for (int index = 0; index < pairs.length; index++) {
            String pair = pairs[index];
            int equals = pair.indexOf('=');
            String name = equals < 0 ? pair : pair.substring(0, equals);
            String decoded = java.net.URLDecoder.decode(name, StandardCharsets.UTF_8);
            result.append(index == 0 ? "" : "&");
            result.append(equals >= 0 && sensitiveQueryParameters.contains(decoded) ? name + "=" + MASK : pair);
        }
        return text(result.toString());
    }

    /** A JSON value with every string (keys included) passed through {@link #text}. */
    Object json(Object value) {
        if (value instanceof String text) {
            return text(text);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, member) -> result.put(text(String.valueOf(key)), json(member)));
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(element -> result.add(json(element)));
            return result;
        }
        return value;
    }
}
