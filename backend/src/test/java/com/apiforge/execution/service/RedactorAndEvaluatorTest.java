package com.apiforge.execution.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.apiforge.environment.domain.Environment.ExecutionPolicy;
import com.apiforge.execution.domain.TestResult.Outcome;
import com.apiforge.outbound.OutboundRequest.Header;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.Expectations.ResponseCheck;
import com.apiforge.testcase.domain.TestCase.RiskLevel;

class RedactorAndEvaluatorTest {

    private final Redactor redactor = new Redactor(List.of("s3cr3t value", "short"), List.of("X-Custom-Key"),
            List.of("api_key"));

    @Test
    void secretsAreRemovedInRawUrlEncodedAndBase64Form() {
        String base64 = Base64.getEncoder().encodeToString("s3cr3t value".getBytes(StandardCharsets.UTF_8));

        assertThat(redactor.text("a s3cr3t value b")).isEqualTo("a «redacted» b");
        assertThat(redactor.text("q=s3cr3t+value&r=s3cr3t%20value")).isEqualTo("q=«redacted»&r=«redacted»");
        assertThat(redactor.text("Basic " + base64)).isEqualTo("Basic «redacted»");
        assertThat(redactor.text("shortcut")).isEqualTo("«redacted»cut");
        assertThat(redactor.text(null)).isNull();
    }

    @Test
    void sensitiveHeadersAreMaskedAndRepeatedHeadersKept() {
        Map<String, Object> headers = redactor.headers(List.of(new Header("Authorization", "Bearer x"),
                new Header("X-Custom-Key", "abc"), new Header("Set-Cookie", "a=1"), new Header("Set-Cookie", "b=2"),
                new Header("X-Trace", "has s3cr3t value inside"), new Header("Content-Type", "application/json")));

        assertThat(headers).containsEntry("Authorization", "«redacted»").containsEntry("X-Custom-Key", "«redacted»")
                .containsEntry("Set-Cookie", List.of("«redacted»", "«redacted»"))
                .containsEntry("X-Trace", "has «redacted» inside").containsEntry("Content-Type", "application/json");
    }

    @Test
    void sensitiveQueryParametersAndNestedJsonAreMasked() {
        assertThat(redactor.url("https://x.example/p?api_key=abc&page=2&api%5Fkey=def"))
                .isEqualTo("https://x.example/p?api_key=«redacted»&page=2&api%5Fkey=«redacted»");
        assertThat(redactor.json(Map.of("s3cr3t value", List.of("ok", "short")))).isEqualTo(
                Map.of("«redacted»", List.of("ok", "«redacted»")));
    }

    // ---- outcome evaluation --------------------------------------------------------------------

    private static final Expectations CREATED = new Expectations(List.of("201"), "Created.", true,
            List.of(new ResponseCheck("201", "application/json", "Item")));

    @ParameterizedTest
    @CsvSource({"200, 2XX, true", "299, 2XX, true", "300, 2XX, false", "404, 404, true", "404, 4XX, true",
            "500, 4XX, false", "418, default, true"})
    void statusesMatchCodesClassesAndDefault(int status, String expected, boolean matches) {
        assertThat(OutcomeEvaluator.matches(expected, String.valueOf(status))).isEqualTo(matches);
    }

    @Test
    void documentedJsonResponsePasses() {
        OutcomeEvaluator.Evaluation evaluation = OutcomeEvaluator.evaluate(CREATED, 201, "application/json; charset=utf-8",
                "{\"id\":1}", false);

        assertThat(evaluation.outcome()).isEqualTo(Outcome.PASSED);
        assertThat(evaluation.checks()).extracting(check -> check.get("name") + "=" + check.get("result")).containsExactly(
                "STATUS=PASS", "CONTENT_TYPE=PASS", "JSON_SYNTAX=PASS", "RESPONSE_SCHEMA=NOT_EVALUATED");
    }

    @Test
    void wrongStatusContentTypeOrSyntaxFails() {
        assertThat(OutcomeEvaluator.evaluate(CREATED, 200, "application/json", "{}", false).checks())
                .extracting(check -> check.get("name") + "=" + check.get("result")).containsExactly("STATUS=FAIL", "JSON_SYNTAX=PASS");
        OutcomeEvaluator.Evaluation html = OutcomeEvaluator.evaluate(CREATED, 201, "text/html", "<html>", false);
        assertThat(html.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(html.checks()).extracting(check -> check.get("name") + "=" + check.get("result"))
                .contains("CONTENT_TYPE=FAIL", "JSON_SYNTAX=FAIL");
        OutcomeEvaluator.Evaluation broken = OutcomeEvaluator.evaluate(CREATED, 201, "application/json",
                "{\"secret\": \"x\"", false);
        assertThat(broken.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(broken.checks().get(2).get("message").toString()).startsWith("The body is not valid JSON: unexpected")
                .doesNotContain("secret");
    }

    @Test
    void truncatedBodiesAreNotParsedAndUndocumentedResponsesOnlyCheckTheStatus() {
        assertThat(OutcomeEvaluator.evaluate(CREATED, 201, "application/json", "{\"a\":", true).checks())
                .extracting(check -> check.get("name") + "=" + check.get("result")).contains("JSON_SYNTAX=NOT_EVALUATED");
        Expectations anyError = new Expectations(List.of("4XX"), "Rejected.", false, List.of());
        assertThat(OutcomeEvaluator.evaluate(anyError, 400, "text/plain", "bad", false).checks()).hasSize(1);
        assertThat(OutcomeEvaluator.evaluate(anyError, 400, null, null, false).outcome()).isEqualTo(Outcome.PASSED);
    }

    @ParameterizedTest
    @CsvSource({"READ_ONLY, READ_ONLY, true", "READ_ONLY, MUTATING, false", "READ_ONLY, DESTRUCTIVE, false",
            "ALLOW_MUTATING, MUTATING, true", "ALLOW_MUTATING, DESTRUCTIVE, false", "ALLOW_DESTRUCTIVE, DESTRUCTIVE, true"})
    void executionPolicies(ExecutionPolicy policy, RiskLevel risk, boolean allowed) {
        assertThat(RunExecutor.allows(policy, risk)).isEqualTo(allowed);
    }

    @Test
    void onlyTextualBodiesAreDecoded() {
        assertThat(RunExecutor.decode("{\"a\":1}".getBytes(StandardCharsets.UTF_8), "application/problem+json"))
                .isEqualTo("{\"a\":1}");
        assertThat(RunExecutor.decode("plain".getBytes(StandardCharsets.UTF_8), null)).isEqualTo("plain");
        assertThat(RunExecutor.decode(new byte[] {(byte) 0xFF, 0, 1}, null)).isNull();
        assertThat(RunExecutor.decode(new byte[] {1, 2}, "image/png")).isNull();
        assertThat(RunExecutor.decode(new byte[0], "text/plain")).isNull();
    }
}
