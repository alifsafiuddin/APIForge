package com.apiforge.common.error;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.common.web.RequestCorrelationFilter;

/**
 * Replaces Spring Boot's default {@code /error} JSON for failures that never reach a controller
 * (for example, errors raised by servlet filters or the container), so every error response
 * has the same problem+json shape.
 */
@RestController
@RequestMapping("${server.error.path:/error}")
public class ProblemErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(ProblemErrorController.class);

    @RequestMapping
    public ResponseEntity<ProblemDetail> handleError(HttpServletRequest request, HttpServletResponse response) {
        HttpStatus status = resolveStatus(request);
        Object exception = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        if (status.is5xxServerError() && exception instanceof Throwable throwable) {
            log.error("Request failed before reaching a controller", throwable);
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detailFor(status));
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        if (originalUri instanceof String uri) {
            problem.setInstance(java.net.URI.create(uri));
        }
        Problems.decorate(problem, ErrorCode.fromStatus(status.value()), request);

        String requestId = RequestCorrelationFilter.currentRequestId(request);
        if (requestId != null) {
            response.setHeader(RequestCorrelationFilter.HEADER, requestId);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(problem);
    }

    private static HttpStatus resolveStatus(HttpServletRequest request) {
        Object code = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (code instanceof Integer value) {
            HttpStatus status = HttpStatus.resolve(value);
            if (status != null && status.isError()) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static String detailFor(HttpStatus status) {
        return switch (status) {
            case NOT_FOUND -> "The requested resource does not exist.";
            case INTERNAL_SERVER_ERROR -> "An unexpected error occurred. Quote the requestId when reporting this problem.";
            default -> status.getReasonPhrase();
        };
    }
}
