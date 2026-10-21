package com.apiforge.outbound;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * One request to an untrusted destination.
 *
 * @param headers          name/value pairs in sending order
 * @param body             request body, or {@code null}
 * @param contentType      Content-Type of {@code body}
 * @param readTimeout      maximum wait for response data (bounded by configuration)
 * @param sensitiveHeaders lower-case header names removed when a redirect changes origin
 */
public record OutboundRequest(String method, String url, List<Header> headers, byte[] body, String contentType,
        Duration readTimeout, boolean followRedirects, Set<String> sensitiveHeaders) {

    public record Header(String name, String value) {
    }

    public OutboundRequest {
        headers = List.copyOf(headers);
        sensitiveHeaders = Set.copyOf(sensitiveHeaders);
    }
}
