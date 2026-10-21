package com.apiforge.testcase.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.apiforge.common.json.JsonValues;

/**
 * The declarative request of a test case. Method and path come from the operation; the host,
 * base URL and credentials always come from the environment at execution time.
 *
 * @param pathParams values for every {@code {name}} in the path (scalars)
 * @param query      query parameters (scalars, or lists of scalars for array parameters)
 * @param headers    request headers other than Content-Type and credentials
 * @param body       what to send as the request body
 * @param auth       how the environment's credentials are applied
 */
public record RequestTemplate(Map<String, Object> pathParams, Map<String, Object> query, Map<String, String> headers,
        Body body, AuthMode auth) {

    public enum AuthMode {
        /** Use the credentials configured in the environment (if any). */
        ENVIRONMENT,
        /** Send no credentials at all. */
        NONE,
        /** Replace the credentials with syntactically valid but wrong ones. */
        INVALID
    }

    public enum BodyMode {
        /** No body and no Content-Type header. */
        NONE,
        /** {@code value} serialized as JSON. */
        JSON,
        /** {@code value} (a string) sent verbatim. */
        RAW
    }

    /** @param contentType media type of the body; {@code null} exactly when {@code mode} is NONE */
    public record Body(BodyMode mode, String contentType, Object value) {

        public static Body none() {
            return new Body(BodyMode.NONE, null, null);
        }

        public static Body json(String contentType, Object value) {
            return new Body(BodyMode.JSON, contentType, value);
        }

        public static Body raw(String contentType, String text) {
            return new Body(BodyMode.RAW, contentType, text);
        }
    }

    public RequestTemplate {
        pathParams = Collections.unmodifiableMap(new LinkedHashMap<>(pathParams));
        query = Collections.unmodifiableMap(new LinkedHashMap<>(query));
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    public RequestTemplate withBody(Body newBody) {
        return new RequestTemplate(pathParams, query, headers, newBody, auth);
    }

    public RequestTemplate withAuth(AuthMode newAuth) {
        return new RequestTemplate(pathParams, query, headers, body, newAuth);
    }

    public RequestTemplate withPathParam(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(pathParams);
        copy.put(name, value);
        return new RequestTemplate(copy, query, headers, body, auth);
    }

    /** Sets ({@code value != null}) or removes ({@code value == null}) a query parameter. */
    public RequestTemplate withQuery(String name, Object value) {
        Map<String, Object> copy = new LinkedHashMap<>(query);
        if (value == null) {
            copy.remove(name);
        } else {
            copy.put(name, value);
        }
        return new RequestTemplate(pathParams, copy, headers, body, auth);
    }

    /** Sets ({@code value != null}) or removes ({@code value == null}) a header. */
    public RequestTemplate withHeader(String name, String value) {
        Map<String, String> copy = new LinkedHashMap<>(headers);
        if (value == null) {
            copy.remove(name);
        } else {
            copy.put(name, value);
        }
        return new RequestTemplate(pathParams, query, copy, body, auth);
    }

    /** Storage form (jsonb). Member order is fixed so stored documents are stable. */
    public Map<String, Object> toMap() {
        Map<String, Object> bodyMap = new LinkedHashMap<>();
        bodyMap.put("mode", body.mode().name());
        if (body.mode() != BodyMode.NONE) {
            bodyMap.put("contentType", body.contentType());
            bodyMap.put("value", JsonValues.deepCopy(body.value()));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("pathParams", JsonValues.deepCopy(pathParams));
        map.put("query", JsonValues.deepCopy(query));
        map.put("headers", new LinkedHashMap<>(headers));
        map.put("body", bodyMap);
        map.put("auth", auth.name());
        return map;
    }

    /** Reads the storage form. Unknown or missing members fall back to "nothing to send". */
    public static RequestTemplate fromMap(Map<?, ?> map) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (map.get("headers") instanceof Map<?, ?> stored) {
            stored.forEach((name, value) -> headers.put(String.valueOf(name), String.valueOf(value)));
        }
        Body body = Body.none();
        if (map.get("body") instanceof Map<?, ?> stored && stored.get("mode") instanceof String mode) {
            BodyMode bodyMode = BodyMode.valueOf(mode.toUpperCase(Locale.ROOT));
            Object contentType = stored.get("contentType");
            body = bodyMode == BodyMode.NONE ? Body.none()
                    : new Body(bodyMode, contentType == null ? null : contentType.toString(),
                            JsonValues.deepCopy(stored.get("value")));
        }
        AuthMode auth = map.get("auth") instanceof String mode ? AuthMode.valueOf(mode) : AuthMode.ENVIRONMENT;
        return new RequestTemplate(JsonValues.copyObject(map.get("pathParams")), JsonValues.copyObject(map.get("query")),
                headers, body, auth);
    }
}
