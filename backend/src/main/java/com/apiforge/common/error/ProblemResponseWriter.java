package com.apiforge.common.error;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * Writes problem+json directly to the servlet response, for components that run outside
 * Spring MVC (security entry points, rate-limit and session filters). Produces exactly the
 * same shape as {@link ApiExceptionHandler}.
 */
@Component
public class ProblemResponseWriter {

    private final JsonMapper jsonMapper;

    public ProblemResponseWriter(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code, String detail)
            throws IOException {
        ProblemDetail problem = Problems.create(code.status(), code, detail, request);
        Map<String, Object> body = new LinkedHashMap<>();
        // Like Spring MVC, omit "type" when it is the default (RFC 9457: absent means "about:blank").
        if (problem.getType() != null) {
            body.put("type", problem.getType().toString());
        }
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        if (problem.getInstance() != null) {
            body.put("instance", problem.getInstance().toString());
        }
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        jsonMapper.writeValue(response.getOutputStream(), body);
    }
}
