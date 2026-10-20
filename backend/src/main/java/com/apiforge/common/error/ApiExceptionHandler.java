package com.apiforge.common.error;

import java.util.Comparator;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.apiforge.common.error.Problems.FieldErrorItem;

/**
 * Converts exceptions raised in Spring MVC into the documented problem+json format.
 * Internal details (exception messages, stack traces) are logged, never returned.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorItem> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldErrorItem(error.getField(), messageOf(error)))
                .sorted(Comparator.comparing(FieldErrorItem::field))
                .toList();
        ProblemDetail problem = validationProblem(request);
        Problems.withErrors(problem, errors);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldErrorItem> errors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new FieldErrorItem(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        ProblemDetail problem = validationProblem(request);
        Problems.withErrors(problem, errors);
        return ResponseEntity.status(problem.getStatus()).headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = Problems.create(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "The request body could not be read. Check that it is valid JSON of the expected shape.",
                servletRequest(request));
        return ResponseEntity.badRequest().headers(headers).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleNoResourceFoundException(NoResourceFoundException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // The default detail ("No static resource ...") exposes framework internals.
        ProblemDetail problem = Problems.create(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND,
                "The requested resource does not exist.", servletRequest(request));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).headers(headers).body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldErrorItem> errors = ex.getConstraintViolations().stream()
                .map(violation -> new FieldErrorItem(violation.getPropertyPath().toString(), violation.getMessage()))
                .sorted(Comparator.comparing(FieldErrorItem::field))
                .toList();
        ProblemDetail problem = Problems.create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "One or more fields are invalid.", request);
        Problems.withErrors(problem, errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception processing {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = Problems.create(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred. Quote the requestId when reporting this problem.", request);
        return ResponseEntity.internalServerError().body(problem);
    }

    /**
     * Every other Spring MVC exception (404, 405, 406, 415, ...) already carries a ProblemDetail;
     * add our members to it so all errors share one shape.
     */
    @Override
    protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem
                && (problem.getProperties() == null || !problem.getProperties().containsKey(Problems.CODE))) {
            Problems.decorate(problem, ErrorCode.fromStatus(statusCode.value()), servletRequest(request));
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ProblemDetail validationProblem(WebRequest request) {
        return Problems.create(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_ERROR,
                "One or more fields are invalid.", servletRequest(request));
    }

    private static String messageOf(FieldError error) {
        return error.getDefaultMessage() != null ? error.getDefaultMessage() : "is invalid";
    }

    private static @Nullable HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof NativeWebRequest nativeRequest
                ? nativeRequest.getNativeRequest(HttpServletRequest.class)
                : null;
    }
}
