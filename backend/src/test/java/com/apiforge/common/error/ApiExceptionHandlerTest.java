package com.apiforge.common.error;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Valid;
import jakarta.validation.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.config.ClockConfig;
import com.apiforge.config.SecurityConfig;

/**
 * Verifies the documented problem+json format for each class of error, using a controller
 * that exists only in this test.
 */
@WebMvcTest(controllers = ApiExceptionHandlerTest.ProbeController.class)
@Import({ ApiExceptionHandlerTest.ProbeController.class, SecurityConfig.class, ProblemResponseWriter.class,
        ClockConfig.class })
@WithMockUser
class ApiExceptionHandlerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void beanValidationFailureListsFieldErrors() throws Exception {
        mockMvc.perform(post("/probe/items").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"quantity\":1000}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.instance").value("/probe/items"))
                .andExpect(jsonPath("$.requestId").isNotEmpty())
                .andExpect(jsonPath("$.errors[*].field", Matchers.containsInAnyOrder("name", "quantity")));
    }

    @Test
    void requestParameterValidationFailureListsParameter() throws Exception {
        mockMvc.perform(get("/probe/search").param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void serviceLevelConstraintViolationListsPropertyPaths() throws Exception {
        mockMvc.perform(get("/probe/constraint"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field", Matchers.contains("name", "quantity")));
    }

    @Test
    void malformedJsonIsRejectedWithoutParserDetails() throws Exception {
        mockMvc.perform(post("/probe/items").with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"name\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("Jackson"))));
    }

    @Test
    void unknownJsonPropertyIsRejected() throws Exception {
        mockMvc.perform(post("/probe/items").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\",\"quantity\":1,\"isAdmin\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unsupportedMethodUsesProblemFormat() throws Exception {
        mockMvc.perform(post("/probe/search").with(csrf()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.requestId").isNotEmpty());
    }

    @Test
    void unsupportedMediaTypeUsesProblemFormat() throws Exception {
        mockMvc.perform(post("/probe/items").with(csrf()).contentType(MediaType.TEXT_PLAIN).content("x"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void unexpectedExceptionHidesInternalMessage() throws Exception {
        mockMvc.perform(get("/probe/fail"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(Matchers.not(Matchers.containsString("secret-internal"))))
                .andExpect(content().string(Matchers.not(Matchers.containsString("secret-internal"))));
    }

    @Test
    void apiExceptionUsesItsCodeStatusAndDetail() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_NAME"))
                .andExpect(jsonPath("$.detail").value("Name already used."));
    }

    @Test
    void optimisticLockFailureIsStaleVersion() throws Exception {
        mockMvc.perform(get("/probe/stale"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    void dataIntegrityViolationHidesConstraintDetails() throws Exception {
        mockMvc.perform(get("/probe/integrity"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("uq_secret_constraint"))));
    }

    @Test
    void accessDeniedInsideControllerIsForbiddenNotServerError() throws Exception {
        mockMvc.perform(get("/probe/denied"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @WithAnonymousUser
    void anonymousRequestIsRejectedByEntryPointWithProblemJson() throws Exception {
        mockMvc.perform(get("/probe/search").param("limit", "1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void postWithoutCsrfTokenIsRejected() throws Exception {
        mockMvc.perform(post("/probe/items").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ok\",\"quantity\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @RestController
    static class ProbeController {

        record CreateItem(@NotBlank String name, @Max(100) int quantity) {
        }

        @PostMapping("/probe/items")
        CreateItem create(@Valid @RequestBody CreateItem item) {
            return item;
        }

        @GetMapping("/probe/search")
        String search(@RequestParam @Max(100) int limit) {
            return "ok";
        }

        @GetMapping("/probe/constraint")
        String constraint() {
            // Simulates validation failing inside a service method (@Validated beans).
            try (var factory = Validation.buildDefaultValidatorFactory()) {
                throw new ConstraintViolationException(factory.getValidator().validate(new CreateItem("", 500)));
            }
        }

        @GetMapping("/probe/conflict")
        String conflict() {
            throw new ApiException(ErrorCode.DUPLICATE_NAME, "Name already used.");
        }

        @GetMapping("/probe/stale")
        String stale() {
            throw new ObjectOptimisticLockingFailureException(Object.class, "id");
        }

        @GetMapping("/probe/integrity")
        String integrity() {
            throw new DataIntegrityViolationException("duplicate key violates uq_secret_constraint");
        }

        @GetMapping("/probe/denied")
        String denied() {
            throw new AccessDeniedException("nope");
        }

        @GetMapping("/probe/fail")
        String fail() {
            throw new IllegalStateException("secret-internal detail that must not leak");
        }
    }
}
