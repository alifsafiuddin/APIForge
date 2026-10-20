package com.apiforge.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.RequestDispatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.apiforge.common.web.RequestCorrelationFilter;

class ProblemErrorControllerTest {

    private final ProblemErrorController controller = new ProblemErrorController();

    @ParameterizedTest
    @CsvSource({
            "400, 400, MALFORMED_REQUEST",
            "404, 404, NOT_FOUND",
            "405, 405, METHOD_NOT_ALLOWED",
            "413, 413, PAYLOAD_TOO_LARGE",
            "503, 503, SERVICE_UNAVAILABLE",
            "500, 500, INTERNAL_ERROR",
            // A non-error status forwarded to /error is not trusted: report a server error.
            "200, 500, INTERNAL_ERROR",
            "999, 500, INTERNAL_ERROR"
    })
    void mapsContainerStatusToProblem(int forwardedStatus, int expectedStatus, String expectedCode) {
        MockHttpServletRequest request = errorRequest(forwardedStatus);

        ResponseEntity<ProblemDetail> response = controller.handleError(request, new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties()).containsEntry("code", expectedCode);
    }

    @Test
    void missingStatusIsTreatedAsServerError() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");

        ResponseEntity<ProblemDetail> response = controller.handleError(request, new MockHttpServletResponse());

        assertThat(response.getStatusCode().value()).isEqualTo(500);
    }

    @Test
    void usesOriginalUriAndRestoresCorrelationHeader() {
        MockHttpServletRequest request = errorRequest(404);
        request.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/api/v1/missing");
        request.setAttribute(RequestCorrelationFilter.REQUEST_ATTRIBUTE, "req-abcdef123");
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        ResponseEntity<ProblemDetail> response = controller.handleError(request, servletResponse);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getInstance()).hasToString("/api/v1/missing");
        assertThat(response.getBody().getProperties()).containsEntry("requestId", "req-abcdef123");
        assertThat(servletResponse.getHeader(RequestCorrelationFilter.HEADER)).isEqualTo("req-abcdef123");
    }

    @Test
    void serverErrorDetailDoesNotExposeExceptionMessage() {
        MockHttpServletRequest request = errorRequest(500);
        request.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException("db password=hunter2"));

        ResponseEntity<ProblemDetail> response = controller.handleError(request, new MockHttpServletResponse());

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).doesNotContain("hunter2");
    }

    private static MockHttpServletRequest errorRequest(int status) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
        return request;
    }
}
