package com.apiforge.testcase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.Problems.FieldErrorItem;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.BodyMode;
import com.apiforge.testcase.service.TestDefinitionValidator.BodyInput;
import com.apiforge.testcase.service.TestDefinitionValidator.ExpectationsInput;
import com.apiforge.testcase.service.TestDefinitionValidator.RequestInput;

class TestDefinitionValidatorTest {

    private static final String OPERATION = "PUT /pets/{petId}/tags/{tag}";
    private static final List<Expectations.ResponseCheck> CHECKS = List.of(
            new Expectations.ResponseCheck("200", "application/json", "Pet"),
            new Expectations.ResponseCheck("400", "application/json", "Error"));

    private static RequestInput request(Map<String, Object> path, Map<String, Object> query, Map<String, String> headers,
            BodyInput body) {
        return new RequestInput(path, query, headers, body, AuthMode.ENVIRONMENT);
    }

    private static RequestInput valid() {
        return request(Map.of("petId", 7, "tag", "x"), Map.of("expand", List.of("owner", "tags"), "dry", true),
                Map.of("X-Trace", "abc"), new BodyInput(BodyMode.JSON, "application/json", Map.of("name", "Rex")));
    }

    private static ExpectationsInput expectations(String... statuses) {
        return new ExpectationsInput(List.of(statuses), "Edited by hand.", null);
    }

    private static List<String> errors(RequestInput request, ExpectationsInput expectations) {
        ApiException ex = catchThrowableOfType(ApiException.class,
                () -> TestDefinitionValidator.validate(OPERATION, request, expectations, CHECKS));
        assertThat(ex).as("expected a validation error").isNotNull();
        return ex.fieldErrors().stream().map(error -> error.field() + ": " + error.message()).toList();
    }

    @Test
    void acceptsAValidDefinitionAndKeepsMatchingResponseChecks() {
        TestDefinitionValidator.Validated validated = TestDefinitionValidator.validate(OPERATION, valid(),
                new ExpectationsInput(List.of("200", "4xx"), " Edited ", false), CHECKS);

        assertThat(validated.request().pathParams()).containsEntry("petId", 7);
        assertThat(validated.request().query()).containsEntry("expand", List.of("owner", "tags"));
        assertThat(validated.expectations().statuses()).containsExactly("200", "4XX");
        assertThat(validated.expectations().summary()).isEqualTo("Edited");
        assertThat(validated.expectations().validateResponseSchema()).isFalse();
        assertThat(validated.expectations().responseChecks()).extracting(Expectations.ResponseCheck::status)
                .containsExactly("200");
    }

    @Test
    void pathParametersMustMatchTheOperationExactly() {
        assertThat(errors(request(Map.of("petId", 7), Map.of(), Map.of(), new BodyInput(BodyMode.NONE, null, null)),
                expectations("200"))).containsExactly("request.pathParams: must set exactly the path parameters petId, tag");
        assertThat(errors(request(Map.of("petId", "", "tag", Map.of()), Map.of(), Map.of(),
                new BodyInput(BodyMode.NONE, null, null)), expectations("200")))
                .contains("request.pathParams.tag: must be a string, number or boolean");
    }

    @Test
    void credentialAndTransportHeadersCannotBeSet() {
        for (String header : List.of("Authorization", "Cookie", "Host", "Content-Length", "Content-Type",
                "Transfer-Encoding", "Proxy-Authorization", "Proxy-Anything")) {
            RequestInput request = request(Map.of("petId", 1, "tag", "t"), Map.of(), Map.of(header, "x"),
                    new BodyInput(BodyMode.NONE, null, null));
            assertThat(errors(request, expectations("200"))).as(header).singleElement().asString()
                    .startsWith("request.headers." + header + ": cannot be set by a test");
        }
    }

    @Test
    void headerInjectionIsRejected() {
        RequestInput request = request(Map.of("petId", 1, "tag", "t"), Map.of(),
                Map.of("X-Evil", "a\r\nInjected: yes", "Bad Name", "x"), new BodyInput(BodyMode.NONE, null, null));

        assertThat(errors(request, expectations("200"))).containsExactlyInAnyOrder(
                "request.headers.X-Evil: must be at most 4000 characters without line breaks",
                "request.headers: header names must be HTTP tokens of 1–100 characters");
    }

    @Test
    void bodyModesAreConsistent() {
        Map<String, Object> path = Map.of("petId", 1, "tag", "t");
        assertThat(errors(request(path, Map.of(), Map.of(), new BodyInput(BodyMode.NONE, "application/json", null)),
                expectations("200"))).containsExactly("request.body: a body of mode NONE has no content type and no value");
        assertThat(errors(request(path, Map.of(), Map.of(), new BodyInput(BodyMode.JSON, "json please", Map.of())),
                expectations("200"))).containsExactly("request.body.contentType: must be a media type such as application/json");
        assertThat(errors(request(path, Map.of(), Map.of(), new BodyInput(BodyMode.RAW, "text/plain", 5)),
                expectations("200"))).containsExactly("request.body.value: must be a string for mode RAW");
        assertThat(errors(request(path, Map.of(), Map.of(), new BodyInput(BodyMode.JSON, "application/json",
                "x".repeat(TestDefinitionValidator.MAX_BODY_BYTES))), expectations("200")))
                .containsExactly("request.body.value: must be at most 256 KB");
        assertThat(errors(request(path, Map.of(), Map.of(), null), expectations("200")))
                .containsExactly("request.body.mode: must be NONE, JSON or RAW");
    }

    @Test
    void queryValuesAreScalarsOrListsOfScalars() {
        Map<String, Object> query = new HashMap<>();
        query.put("nested", Map.of("a", 1));
        query.put("deep", List.of(List.of(1)));
        query.put("ctrl", "a\u0000b");
        RequestInput request = request(Map.of("petId", 1, "tag", "t"), query, Map.of(), new BodyInput(BodyMode.NONE, null,
                null));

        assertThat(errors(request, expectations("200"))).containsExactlyInAnyOrder(
                "request.query.nested: must be a string, number, boolean or a list of them",
                "request.query.deep: must be a list of at most 100 strings, numbers or booleans",
                "request.query.ctrl: must be a string, number, boolean or a list of them");
    }

    @Test
    void statusesMustBeCodesOrClasses() {
        assertThat(errors(valid(), expectations("200", "600", "abc", "2xx"))).containsExactly(
                "expectations.statuses: '600' is not a status code (100–599) or class (1XX–5XX)",
                "expectations.statuses: 'abc' is not a status code (100–599) or class (1XX–5XX)");
        assertThat(errors(valid(), new ExpectationsInput(List.of(), null, null)))
                .containsExactly("expectations.statuses: must list at least one status, e.g. 200 or 4XX");
    }

    @Test
    void missingAuthModeIsReported() {
        RequestInput request = new RequestInput(Map.of("petId", 1, "tag", "t"), Map.of(), Map.of(),
                new BodyInput(BodyMode.NONE, null, null), null);

        assertThat(errors(request, expectations("200"))).containsExactly("request.auth: must be ENVIRONMENT, NONE or INVALID");
    }

    @Test
    void pathVariablesAreReadFromTheOperationKey() {
        assertThat(TestDefinitionValidator.pathVariables("GET /a/{x}/b/{y.z}")).containsExactly("x", "y.z");
        assertThat(TestDefinitionValidator.pathVariables("GET /")).isEmpty();
        assertThat(new FieldErrorItem("f", "m").field()).isEqualTo("f");
    }
}
