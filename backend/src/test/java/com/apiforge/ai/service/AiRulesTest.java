package com.apiforge.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.apiforge.ai.AiProperties;
import com.apiforge.ai.client.AiFailure;
import com.apiforge.ai.client.AiFailure.Kind;
import com.apiforge.ai.client.OllamaGateway;
import com.apiforge.ai.service.AiViews.Grounding;
import com.apiforge.ai.service.StructuredOutput.ExplanationReply;
import com.apiforge.ai.service.StructuredOutput.SuggestionReply;

class AiRulesTest {

    static AiProperties properties(boolean enabled, int maxPromptChars, int maxAttempts) {
        return new AiProperties(enabled, "http://127.0.0.1:1", "m:1b", Duration.ofSeconds(1), Duration.ofSeconds(2),
                Duration.ofSeconds(1), maxAttempts, 1, Duration.ofMillis(100), 120, maxPromptChars, 4096, 512, 0.2, "1m", 5);
    }

    private static final String EXPLANATION = """
            {"summary":"The name was too long.","observedFacts":[{"statement":"Status 201 was received.","source":"RESPONSE"}],
             "likelyCauses":[{"hypothesis":"Validation is missing.","confidence":"LOW","evidence":["201"]}],
             "debuggingSteps":["Repeat the request."],"suggestedChanges":[]}""";

    @Nested
    class Output {

        @Test
        void validAnswersAreBound() {
            ExplanationReply reply = StructuredOutput.read(EXPLANATION, StructuredOutput.EXPLANATION, ExplanationReply.class);

            assertThat(reply.observedFacts()).singleElement().satisfies(fact -> assertThat(fact.source()).isEqualTo("RESPONSE"));
            assertThat(StructuredOutput.read("```json\n" + EXPLANATION + "\n```", StructuredOutput.EXPLANATION,
                    ExplanationReply.class).summary()).isEqualTo("The name was too long.");
            assertThat(StructuredOutput.read("{\"suggestions\":[]}", StructuredOutput.SUGGESTIONS, SuggestionReply.class)
                    .suggestions()).isEmpty();
            // A null body value (seen from llama3.2:3b in the evaluation) is valid structure.
            assertThat(StructuredOutput.read("""
                    {"suggestions":[{"name":"No body at all","category":"CUSTOM","rationale":"The body is required by the contract.",
                      "request":{"pathParams":{},"query":{},"headers":{},"body":{"mode":"NONE","value":null},"auth":"ENVIRONMENT"},
                      "expectedStatuses":["400"],"duplicateOf":null,"duplicateReason":null}]}""",
                    StructuredOutput.SUGGESTIONS, SuggestionReply.class).suggestions()).hasSize(1);
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "Here you go: {\"suggestions\":[]}", "{\"suggestions\":[]} trailing",
                "{\"suggestions\":[{\"name\":\"x\"}]}", "{\"suggestions\":[], \"extra\": 1}", "[]", "{\"suggestions\": \"none\"}"})
        void anythingElseIsInvalidOutput(String content) {
            assertThatThrownBy(() -> StructuredOutput.read(content, StructuredOutput.SUGGESTIONS, SuggestionReply.class))
                    .isInstanceOfSatisfying(AiFailure.class, failure -> {
                        assertThat(failure.kind()).isEqualTo(Kind.INVALID_OUTPUT);
                        assertThat(failure.getMessage()).doesNotContain("trailing").doesNotContain("Here you go");
                    });
        }

        @Test
        void schemaProblemsAreNamedByPath() {
            String wrongEnum = EXPLANATION.replace("\"LOW\"", "\"CERTAIN\"");

            assertThatThrownBy(() -> StructuredOutput.read(wrongEnum, StructuredOutput.EXPLANATION, ExplanationReply.class))
                    .hasMessageContaining("$.likelyCauses[0].confidence");
        }
    }

    @Nested
    class Sanitizing {

        @Test
        void sensitiveValuesTokensAndOpaqueStringsAreRemoved() {
            Object clean = PromptSanitizer.value(Map.of(
                    "password", "hunter2", "apiKey", "k", "nested", Map.of("client_secret", "s", "name", "Drill"),
                    "note", "Authorization: Bearer abcdefghijklmnop and jwt eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.c2lnbmF0dXJl",
                    "id", "3f2504e0-4f89-11d3-9a0c-0305e82c3301",
                    "hash", "0123456789abcdef0123456789abcdef0123"));

            assertThat(clean).isEqualTo(Map.of("password", "«redacted»", "apiKey", "«redacted»",
                    "nested", Map.of("client_secret", "«redacted»", "name", "Drill"),
                    "note", "Authorization: Bearer «redacted» and jwt «redacted»",
                    "id", "3f2504e0-4f89-11d3-9a0c-0305e82c3301", "hash", "«redacted»"));
        }

        @Test
        void sizesAreBounded() {
            assertThat(PromptSanitizer.text("word ".repeat(200), 100)).hasSizeLessThan(140).contains("more characters");
            assertThat((List<?>) PromptSanitizer.value(java.util.Collections.nCopies(50, 1))).hasSize(PromptSanitizer.MAX_ITEMS + 1);
            assertThat(PromptSanitizer.body("{\"token\":\"t\",\"items\":[1]}", "application/json", 1000))
                    .isEqualTo("{\"token\":\"«redacted»\",\"items\":[1]}");
        }

        @Test
        void onlyHarmlessHeadersAreKept() {
            assertThat(PromptSanitizer.headers(Map.of("Content-Type", "application/json", "Set-Cookie", "s=1",
                    "X-Internal-Host", "db-7"))).containsOnlyKeys("Content-Type");
            assertThat(PromptSanitizer.requestHeaders(Map.of("X-Debug", "1", "X-API-Key", "«redacted»", "X-Token", "t",
                    "X-APIForge-Request-Id", "c"), java.util.Set.of("x-apiforge-request-id"))).containsOnlyKeys("X-Debug");
        }

        @Test
        void schemasAreInlinedToABoundedDepthAndCyclesStop() {
            Map<String, Object> components = Map.of("Node", Map.of("type", "object", "properties",
                    Map.of("child", Map.of("$ref", "#/components/schemas/Node"))));

            Object inlined = PromptSanitizer.schema(Map.of("$ref", "#/components/schemas/Node"), components, 2);

            assertThat(inlined.toString()).contains("title=Node").contains("$ref=Node");
        }
    }

    @Nested
    class Grounding_ {

        private static final String CONTEXT = """
                {"failedAssertions":[{"name":"STATUS","expected":"400","actual":"201"},
                 {"name":"BODY_SCHEMA","path":"$.name","actual":"string \\"aaaaaaaaaaaaaaaaaaaaaaaaa\\""}],
                 "response":{"status":201}}""".toLowerCase();

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                The test expected 400 and got 201.                | VERIFIED
                The check at $.name failed.                       | VERIFIED
                The value "aaaaaaaaaaaaaaaaaaaaaaaaa" is too long. | VERIFIED
                The API answered 500.                             | UNVERIFIED
                The element $.items[3] is missing.                | UNVERIFIED
                The API seems to skip validation.                 | NOT_CHECKABLE
                """)
        void citedValuesMustAppearInTheData(String statement, Grounding expected) {
            assertThat(FailureExplanationService.grounding(statement, CONTEXT)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"I reviewed the source code of the controller.", "We have inspected your implementation.",
                "The bug is in ItemController.java:42.", "See the check at line 17."})
        void claimsOfSourceCodeAccessAreDetected(String text) {
            assertThat(FailureExplanationService.claimsSourceCode(text, CONTEXT)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"The implementation may not validate the length.", "Check the server logs for the request."})
        void hypothesesAboutTheImplementationAreFine(String text) {
            assertThat(FailureExplanationService.claimsSourceCode(text, CONTEXT)).isFalse();
        }

        @Test
        void phrasesQuotedFromTheDataAreNotClaims() {
            // Found by the evaluation: both models quoted the parser's position of a malformed body.
            String context = "{\"message\":\"the body is not valid json at line 1, column 17.\"}";

            assertThat(FailureExplanationService.claimsSourceCode("The body is not valid JSON at line 1, column 17.", context))
                    .isFalse();
            assertThat(FailureExplanationService.claimsSourceCode("The parser in Api.java:12 fails at line 9.", context)).isTrue();
        }

        @Test
        void annotatedExplanationsSayWhatWasProvided() {
            Map<String, Object> stored = FailureExplanationService.annotate(
                    StructuredOutput.read(EXPLANATION, StructuredOutput.EXPLANATION, ExplanationReply.class), CONTEXT);

            assertThat(stored).containsEntry("sourceCodeProvided", false).containsEntry("warnings", List.of());
            assertThat(stored.get("inputs")).isEqualTo(FailureExplanationService.INPUTS);
        }
    }

    @Nested
    class Status {

        @ParameterizedTest
        @CsvSource({"qwen2.5:7b, qwen2.5:7b, true", "llama3.2, llama3.2:latest, true", "llama3.2:3b, llama3.2:1b, false",
                "mistral, mistral-nemo:latest, false"})
        void modelNamesMatchWithTheLatestTag(String configured, String installed, boolean expected) {
            assertThat(AiStatusService.installed(List.of(installed), configured)).isEqualTo(expected);
        }
    }

    @Nested
    class Runner {

        private final AtomicInteger calls = new AtomicInteger();

        private AiCallRunner runner(AiProperties properties, String... answers) {
            OllamaGateway gateway = new OllamaGateway(properties) {
                @Override
                public Completion chat(String system, String user, Map<String, Object> schema) {
                    int call = calls.getAndIncrement();
                    String answer = answers[Math.min(call, answers.length - 1)];
                    if (answer.equals("OVERLOADED")) {
                        throw new AiFailure(Kind.OVERLOADED, "busy");
                    }
                    if (answer.equals("CUT")) {
                        return new Completion("{\"summ", "m:1b", "length", 1, 1, 512);
                    }
                    return new Completion(answer, "m:1b", "stop", 1, 1, 1);
                }
            };
            return new AiCallRunner(gateway, properties, null, new AiStatusService(gateway, properties, Clock.systemUTC()),
                    Clock.systemUTC());
        }

        private ExplanationReply call(AiCallRunner runner, String user) {
            return runner.call(UUID.randomUUID(), "test", "system", user, StructuredOutput.EXPLANATION,
                    content -> StructuredOutput.read(content, StructuredOutput.EXPLANATION, ExplanationReply.class),
                    new AiCallRunner.Metrics());
        }

        @Test
        void disabledAndOversizedRequestsNeverReachTheModel() {
            assertThatThrownBy(() -> call(runner(properties(false, 2000, 2), EXPLANATION), "u"))
                    .isInstanceOfSatisfying(AiFailure.class, failure -> assertThat(failure.kind()).isEqualTo(Kind.DISABLED));
            assertThatThrownBy(() -> call(runner(properties(true, 2000, 2), EXPLANATION), "u".repeat(2001)))
                    .isInstanceOfSatisfying(AiFailure.class, failure -> assertThat(failure.kind()).isEqualTo(Kind.TOO_LARGE));
            assertThat(calls).hasValue(0);
        }

        @Test
        void attemptsAreBounded() {
            assertThatThrownBy(() -> call(runner(properties(true, 2000, 3), "nope"), "u"))
                    .isInstanceOfSatisfying(AiFailure.class, failure -> assertThat(failure.kind()).isEqualTo(Kind.INVALID_OUTPUT));
            assertThat(calls).hasValue(3);
        }

        @Test
        void aCutOffAnswerIsReportedAsSuch() {
            assertThatThrownBy(() -> call(runner(properties(true, 2000, 1), "CUT"), "u"))
                    .hasMessageContaining("cut off at the output token limit");
        }

        @Test
        void overloadIsRetriedAfterAPause() {
            AiCallRunner.Metrics metrics = new AiCallRunner.Metrics();
            ExplanationReply reply = runner(properties(true, 2000, 2), "OVERLOADED", EXPLANATION).call(UUID.randomUUID(), "t",
                    "s", "u", StructuredOutput.EXPLANATION,
                    content -> StructuredOutput.read(content, StructuredOutput.EXPLANATION, ExplanationReply.class), metrics);

            assertThat(reply.summary()).isNotBlank();
            assertThat(metrics.attempts()).isEqualTo(2);
            assertThat(metrics.latencyMs()).isGreaterThanOrEqualTo(1900);
        }
    }

    @Nested
    class NearDuplicates {

        private final com.apiforge.testcase.domain.TestCase existing = com.apiforge.testcase.domain.TestCase.suggested(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "POST /items", "Long name",
                com.apiforge.testcase.domain.TestCase.Category.STRING_LENGTH,
                com.apiforge.testcase.domain.RequestTemplate.fromMap(com.apiforge.schema.Schemas.object("""
                        {"pathParams":{},"query":{"dry":"true"},"headers":{},"body":{"mode":"JSON","contentType":"application/json",
                         "value":{"name":"aaaa","tags":["x"]}},"auth":"ENVIRONMENT"}""")),
                com.apiforge.testcase.domain.Expectations.fromMap(Map.of("statuses", List.of("400"), "summary", "s",
                        "validateResponseSchema", true, "responseChecks", List.of())),
                "r", "m", "p", "0".repeat(64), 0);

        private static Map<String, Object> request(String value, String tag, String auth) {
            return com.apiforge.schema.Schemas.object("""
                    {"pathParams":{},"query":{"dry":"true"},"headers":{},"body":{"mode":"JSON","contentType":"application/json",
                     "value":{"name":"%s","tags":["%s"]}},"auth":"%s"}""".formatted(value, tag, auth));
        }

        @Test
        void oneDifferingValueIsAPossibleDuplicate() {
            assertThat(TestSuggestionService.nearDuplicate(existing, request("bbbb", "x", "ENVIRONMENT"), List.of("400")))
                    .isEqualTo("body/name");
        }

        @Test
        void moreDifferencesOtherStatusesOrOtherAuthAreNot() {
            assertThat(TestSuggestionService.nearDuplicate(existing, request("bbbb", "y", "ENVIRONMENT"), List.of("400"))).isNull();
            assertThat(TestSuggestionService.nearDuplicate(existing, request("bbbb", "x", "ENVIRONMENT"), List.of("422"))).isNull();
            assertThat(TestSuggestionService.nearDuplicate(existing, request("aaaa", "x", "NONE"), List.of("400")))
                    .isEqualTo("auth");
        }
    }

    @Nested
    class Configuration {

        @ParameterizedTest
        @ValueSource(strings = {"ftp://host:11434", "http://user:pw@host:11434", "http://host:11434/?x=1", "not a url", "localhost:11434"})
        void unsafeBaseUrlsAreRefusedAtStartup(String url) {
            assertThatThrownBy(() -> new AiProperties(true, url, "m:1b", Duration.ofSeconds(1), Duration.ofSeconds(2),
                    Duration.ofSeconds(1), 2, 1, Duration.ofMillis(100), 10, 2000, 4096, 512, 0.2, "1m", 5))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void theOriginNeverCarriesAPath() {
            assertThat(properties(true, 2000, 2).origin()).isEqualTo("http://127.0.0.1:1");
        }
    }
}
