package com.apiforge.outbound;

import java.util.List;

import com.apiforge.outbound.OutboundRequest.Header;

/** What came back from an outbound request: a response, or a classified failure. */
public sealed interface OutboundResult permits OutboundResult.Response, OutboundResult.Failure {

    long durationMs();

    /** URLs requested after the first one (redirects that were followed). */
    List<String> redirects();

    /**
     * @param body       at most the configured response limit; {@code truncated} when the response was larger
     * @param sizeBytes  bytes received (a lower bound when truncated, unless Content-Length said more)
     */
    record Response(int status, List<Header> headers, byte[] body, long sizeBytes, boolean truncated,
            String contentType, long durationMs, List<String> redirects) implements OutboundResult {

        public Response {
            headers = List.copyOf(headers);
            redirects = List.copyOf(redirects);
        }
    }

    record Failure(Kind kind, String message, long durationMs, List<String> redirects) implements OutboundResult {

        public Failure {
            redirects = List.copyOf(redirects);
        }
    }

    enum Kind {
        BLOCKED_BY_POLICY, REDIRECT_BLOCKED, DNS_FAILURE, CONNECT_FAILED, TIMEOUT, TLS_ERROR, INVALID_RESPONSE,
        CANCELLED, INTERNAL
    }
}
