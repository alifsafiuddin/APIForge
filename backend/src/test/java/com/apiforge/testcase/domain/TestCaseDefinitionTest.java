package com.apiforge.testcase.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.apiforge.testcase.domain.RequestTemplate.AuthMode;
import com.apiforge.testcase.domain.RequestTemplate.Body;
import com.apiforge.testcase.domain.TestCase.Definition;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class TestCaseDefinitionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID PROJECT = UUID.randomUUID();
    private static final UUID SUITE = UUID.randomUUID();
    private static final UUID VERSION_1 = UUID.randomUUID();
    private static final UUID VERSION_2 = UUID.randomUUID();

    private static Definition definition(String name, Object bodyValue) {
        RequestTemplate request = new RequestTemplate(Map.of("id", 5L), Map.of("q", List.of("a", "b")),
                Map.of("X-Trace", "1"), Body.json("application/json", bodyValue), AuthMode.ENVIRONMENT);
        Expectations expectations = new Expectations(List.of("400", "422"), "Rejected.", true,
                List.of(new Expectations.ResponseCheck("400", "application/json", "Error")));
        return new Definition("POST /pets/{id}", "body.missing:/name", name, TestCase.Category.MISSING_FIELD,
                TestCase.RiskLevel.MUTATING, request, expectations, "'name' is required.", "#/components/schemas/Pet",
                3, "1");
    }

    /** What Hibernate stores and reads back: a JSON round trip. */
    private static Map<String, Object> roundTrip(Map<String, Object> map) {
        return JSON.readValue(JSON.writeValueAsString(map), new TypeReference<>() {
        });
    }

    @Test
    void requestAndExpectationsSurviveTheStorageFormat() {
        Definition definition = definition("Missing name", Map.of("tag", 1.5));

        RequestTemplate request = RequestTemplate.fromMap(roundTrip(definition.request().toMap()));
        Expectations expectations = Expectations.fromMap(roundTrip(definition.expectations().toMap()));

        assertThat(request.pathParams()).containsEntry("id", 5);
        assertThat(request.query()).containsEntry("q", List.of("a", "b"));
        assertThat(request.headers()).containsEntry("X-Trace", "1");
        assertThat(request.body()).isEqualTo(Body.json("application/json", Map.of("tag", 1.5)));
        assertThat(request.auth()).isEqualTo(AuthMode.ENVIRONMENT);
        assertThat(expectations).isEqualTo(definition.expectations());
    }

    @Test
    void bodyModesAndJsonNullAreKept() {
        Map<String, Object> nullBody = new LinkedHashMap<>();
        nullBody.put("note", null);
        RequestTemplate json = new RequestTemplate(Map.of(), Map.of(), Map.of(), Body.json("application/json", nullBody),
                AuthMode.NONE);
        RequestTemplate raw = json.withBody(Body.raw("text/plain", "{}"));
        RequestTemplate none = json.withBody(Body.none());

        assertThat(RequestTemplate.fromMap(roundTrip(json.toMap())).body().value()).isEqualTo(nullBody);
        assertThat(RequestTemplate.fromMap(roundTrip(raw.toMap())).body()).isEqualTo(Body.raw("text/plain", "{}"));
        assertThat(RequestTemplate.fromMap(roundTrip(none.toMap())).body()).isEqualTo(Body.none());
        assertThat(none.toMap().get("body")).isEqualTo(Map.of("mode", "NONE"));
        assertThat(RequestTemplate.fromMap(Map.of()).auth()).isEqualTo(AuthMode.ENVIRONMENT);
    }

    @Test
    void identityDependsOnOperationAndScenarioOnly() {
        assertThat(definition("A", Map.of()).dedupeHash()).isEqualTo(definition("B", Map.of("x", 1)).dedupeHash())
                .isEqualTo(TestCase.dedupeHash("POST /pets/{id}", "body.missing:/name")).matches("[0-9a-f]{64}");
        assertThat(TestCase.dedupeHash("POST /pets/{id}", "body.missing:/tag"))
                .isNotEqualTo(TestCase.dedupeHash("POST /pets/{id}", "body.missing:/name"));
        assertThat(TestCase.dedupeHash("PUT /pets/{id}", "body.missing:/name"))
                .isNotEqualTo(TestCase.dedupeHash("POST /pets/{id}", "body.missing:/name"));
    }

    @Test
    void storedTestMatchesItsDefinitionAfterARoundTripAndRefreshKeepsUserChoices() {
        Definition original = definition("Missing name", Map.of("tag", 1L));
        TestCase test = TestCase.generated(PROJECT, SUITE, VERSION_1, original);
        assertThat(test.getOrigin()).isEqualTo(TestCase.Origin.DETERMINISTIC);
        assertThat(test.getReviewStatus()).isEqualTo(TestCase.ReviewStatus.APPROVED);
        assertThat(test.getScenarioKey()).isEqualTo("body.missing:/name");
        assertThat(test.getSortOrder()).isEqualTo(3);
        assertThat(test.isExecutable()).isTrue();

        assertThat(test.matches(original)).isTrue();
        assertThat(test.matches(definition("Missing name", Map.of("tag", 1.0)))).as("1 and 1.0 are equal JSON").isTrue();
        Definition changed = definition("Name is required", Map.of("tag", 2L));
        assertThat(test.matches(changed)).isFalse();

        test.setEnabled(false);
        UUID otherSuite = UUID.randomUUID();
        test.moveTo(otherSuite);
        test.refresh(changed, VERSION_2);

        assertThat(test.getName()).isEqualTo("Name is required");
        assertThat(test.getSourceSpecificationVersionId()).isEqualTo(VERSION_2);
        assertThat(test.isEnabled()).isFalse();
        assertThat(test.getSuiteId()).isEqualTo(otherSuite);
        assertThat(test.isUserModified()).isFalse();
        assertThat(test.isExecutable()).as("disabled tests never run").isFalse();
    }

    @Test
    void editingMarksTheTestAndSendsAiSuggestionsBackToReview() {
        TestCase generated = TestCase.generated(PROJECT, SUITE, VERSION_1, definition("Missing name", Map.of()));
        Definition source = definition("x", Map.of());
        TestCase suggestion = new TestCase(PROJECT, SUITE, "POST /pets/{id}", "AI idea", TestCase.Category.CUSTOM,
                TestCase.Origin.AI_SUGGESTED, TestCase.RiskLevel.MUTATING, source.request().toMap(),
                source.expectations().toMap(), TestCase.dedupeHash("POST /pets/{id}", "ai"));
        suggestion.review(TestCase.ReviewStatus.APPROVED, UUID.randomUUID(), Instant.now());

        generated.edit("Edited", source.request(), source.expectations(), "mine");
        suggestion.edit("Edited", source.request(), source.expectations(), null);

        assertThat(generated.isUserModified()).isTrue();
        assertThat(generated.getReviewStatus()).isEqualTo(TestCase.ReviewStatus.APPROVED);
        assertThat(generated.getRationale()).isEqualTo("mine");
        assertThat(suggestion.getReviewStatus()).isEqualTo(TestCase.ReviewStatus.PENDING_REVIEW);
        assertThat(suggestion.getReviewedAt()).isNull();
        assertThat(suggestion.isExecutable()).isFalse();
    }

    @Test
    void archivedTestsAreNotExecutable() {
        TestCase test = TestCase.generated(PROJECT, SUITE, VERSION_1, definition("Missing name", Map.of()));
        test.archive();

        assertThat(test.getLifecycleStatus()).isEqualTo(TestCase.LifecycleStatus.ARCHIVED);
        assertThat(test.isExecutable()).isFalse();
    }
}
