package com.apiforge.common.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Read-only access to metadata of the current HTTP request (if any) for cross-cutting
 * concerns such as auditing. Returns {@code null} outside a request.
 */
public final class RequestMetadata {

    private RequestMetadata() {
    }

    /**
     * The client address as seen by the servlet container. Behind a reverse proxy, enable
     * {@code server.forward-headers-strategy} so this reflects the real client; headers such as
     * {@code X-Forwarded-For} are never trusted directly.
     */
    public static String clientIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getRemoteAddr();
    }

    public static String requestId() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : RequestCorrelationFilter.currentRequestId(request);
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest()
                : null;
    }
}
