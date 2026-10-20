package com.apiforge.common.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a correlation id, exposes it in the {@code X-Request-Id} response
 * header and the logging MDC, and writes one access-log line per request.
 *
 * <p>A client-supplied id is reused only if it is short and limited to a safe character set,
 * so it cannot inject content into logs or headers. The access log deliberately omits the query
 * string, headers and bodies because they may carry credentials.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestCorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    public static final String REQUEST_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";

    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{8,64}$");
    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("com.apiforge.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = resolveRequestId(request.getHeader(HEADER));
        long startNanos = System.nanoTime();
        MDC.put(MDC_KEY, requestId);
        request.setAttribute(REQUEST_ATTRIBUTE, requestId);
        response.setHeader(HEADER, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startNanos) / 1_000_000;
            ACCESS_LOG.info("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), durationMs);
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // The error dispatch reuses the id assigned on the original request.
        return true;
    }

    static String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    /** Returns the correlation id for the current request, or {@code null} outside a request. */
    public static String currentRequestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : MDC.get(MDC_KEY);
    }
}
