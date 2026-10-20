package com.apiforge.common.error;

import java.net.URI;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

import com.apiforge.common.web.RequestCorrelationFilter;

/**
 * Builds RFC 9457 problem responses in the format documented in docs/API_DESIGN.md:
 * standard members plus {@code code}, {@code requestId} and optional {@code errors}.
 */
public final class Problems {

    public static final String CODE = "code";
    public static final String REQUEST_ID = "requestId";
    public static final String ERRORS = "errors";

    private Problems() {
    }

    public static ProblemDetail create(HttpStatusCode status, ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        return decorate(problem, code, request);
    }

    /** Adds APIForge members to a problem created elsewhere (for example by Spring MVC). */
    public static ProblemDetail decorate(ProblemDetail problem, ErrorCode code, HttpServletRequest request) {
        problem.setProperty(CODE, code.name());
        if (request != null) {
            if (problem.getInstance() == null) {
                problem.setInstance(URI.create(request.getRequestURI()));
            }
            String requestId = RequestCorrelationFilter.currentRequestId(request);
            if (requestId != null) {
                problem.setProperty(REQUEST_ID, requestId);
            }
        }
        return problem;
    }

    public static ProblemDetail withErrors(ProblemDetail problem, List<FieldErrorItem> errors) {
        problem.setProperty(ERRORS, errors);
        return problem;
    }

    /** A single input problem: the offending field (or parameter) and a human-readable message. */
    public record FieldErrorItem(String field, String message) {
    }
}
