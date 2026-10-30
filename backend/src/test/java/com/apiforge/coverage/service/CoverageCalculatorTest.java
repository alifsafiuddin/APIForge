package com.apiforge.coverage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.apiforge.coverage.service.CoverageCalculator.OperationInput;
import com.apiforge.coverage.service.CoverageCalculator.PlannedScenario;
import com.apiforge.coverage.service.CoverageCalculator.ResultInput;
import com.apiforge.coverage.service.CoverageCalculator.TestInput;
import com.apiforge.coverage.service.CoverageViews.Metric;
import com.apiforge.coverage.service.CoverageViews.NegativeState;
import com.apiforge.coverage.service.CoverageViews.OperationDetail;
import com.apiforge.coverage.service.CoverageViews.OperationRow;
import com.apiforge.coverage.service.CoverageViews.OutcomeCounts;
import com.apiforge.execution.domain.TestResult.Outcome;

/**
 * Every metric on one hand-built project; the expected numbers are worked out in the comments.
 *
 * <pre>
 * A  GET /items        responses 200, 401, 4XX   planned: happy, auth.none, limit below-minimum,
 *                                                limit at-minimum, limit type
 * B  POST /orders      responses 201, 400, default  planned: happy, body.missing, body.type:/qty,
 *                                                content-type; 2 oneOf branches
 * C  DELETE /items/{id} responses 204            planned: happy, auth.none; no tests
 *
 * t1 A happy        200     f1   SKIPPED@T1, PASSED 200@T2, FAILED 200@T3
 * t4 A (hand copy)  200     f1   PASSED 200@T4           -> duplicate of t1, counted once
 * t2 A auth.none    401     f2   ERROR (no response)@T2
 * t3 A below-min    400     f3   SKIPPED@T1 only
 * t5 A limit type   400     f5   disabled; FAILED 418@T2 (a fact about the API, still exercises 4XX)
 * t6 B happy        201     f6   branch 0; PASSED 201@T2, INCONCLUSIVE 201@T5
 * t7 B body.missing 400     f7   FAILED 500@T2           -> 500 is covered by "default"
 * t8 B body.type    4XX     f8   no results
 * a deleted test of A received 302 (undocumented); one of C received 204
 * </pre>
 */
class CoverageCalculatorTest {

    private static final Instant T1 = Instant.parse("2026-10-22T10:00:00Z");
    private static final Instant T2 = T1.plusSeconds(60);
    private static final Instant T3 = T1.plusSeconds(120);
    private static final Instant T4 = T1.plusSeconds(180);
    private static final Instant T5 = T1.plusSeconds(240);

    private static final String A = "GET /items";
    private static final String B = "POST /orders";
    private static final String C = "DELETE /items/{id}";

    private static final UUID T1D = id(1);
    private static final UUID T2D = id(2);
    private static final UUID T3D = id(3);
    private static final UUID T4D = id(4);
    private static final UUID T5D = id(5);
    private static final UUID T6D = id(6);
    private static final UUID T7D = id(7);
    private static final UUID T8D = id(8);

    private static UUID id(int n) {
        return UUID.fromString("00000000-0000-0000-0000-00000000000" + n);
    }

    private static PlannedScenario planned(String key) {
        return new PlannedScenario(key, key, ScenarioKind.of(key));
    }

    private static SchemaBranches.Branch branch(int index) {
        return new SchemaBranches.Branch("delivery#oneOf/" + index, List.of("delivery"), "delivery: one of " + index, null);
    }

    private static final List<OperationInput> OPERATIONS = List.of(
            new OperationInput(A, "GET", "/items", List.of("items"), false, List.of("200", "401", "4XX"),
                    List.of(planned("happy.minimal"), planned("auth.none"), planned("query.below-minimum:limit"),
                            planned("query.at-minimum:limit"), planned("query.type:limit")),
                    List.of(), List.of()),
            new OperationInput(B, "POST", "/orders", List.of("orders"), false, List.of("201", "400", "default"),
                    List.of(planned("happy.minimal"), planned("body.missing"), planned("body.type:/qty"),
                            planned("content-type.unsupported")),
                    List.of(new CoverageViews.SkippedView("Field scenarios inside field 'delivery'", "oneOf")),
                    List.of(branch(0), branch(1))),
            new OperationInput(C, "DELETE", "/items/{id}", List.of("items"), true, List.of("204"),
                    List.of(planned("happy.minimal"), planned("auth.none")), List.of(), List.of()));

    private static TestInput test(UUID id, String operation, String name, String scenario, boolean enabled,
            String fingerprint, List<String> statuses, Set<String> branches, int order) {
        return new TestInput(id, operation, name, scenario, enabled, fingerprint, statuses, branches, order);
    }

    private static final List<TestInput> TESTS = List.of(
            test(T1D, A, "Valid", "happy.minimal", true, "f1", List.of("200"), Set.of(), 0),
            test(T2D, A, "No credentials", "auth.none", true, "f2", List.of("401"), Set.of(), 1),
            test(T3D, A, "limit below", "query.below-minimum:limit", true, "f3", List.of("400"), Set.of(), 2),
            test(T4D, A, "Copy of valid", null, true, "f1", List.of("200"), Set.of(), 9),
            test(T5D, A, "limit type", "query.type:limit", false, "f5", List.of("400"), Set.of(), 3),
            test(T6D, B, "Valid order", "happy.minimal", true, "f6", List.of("201"), Set.of("delivery#oneOf/0"), 0),
            test(T7D, B, "No body", "body.missing", true, "f7", List.of("400"), Set.of(), 1),
            test(T8D, B, "qty type", "body.type:/qty", true, "f8", List.of("4XX"), Set.of(), 2));

    private static ResultInput result(UUID test, String operation, Outcome outcome, Integer status, Instant at) {
        return new ResultInput(test, operation, outcome, status, at);
    }

    private static final List<ResultInput> RESULTS = List.of(
            result(T1D, A, Outcome.SKIPPED, null, T1),
            result(T1D, A, Outcome.PASSED, 200, T2),
            result(T1D, A, Outcome.FAILED, 200, T3),
            result(T4D, A, Outcome.PASSED, 200, T4),
            result(T2D, A, Outcome.ERROR, null, T2),
            result(T3D, A, Outcome.SKIPPED, null, T1),
            result(T5D, A, Outcome.FAILED, 418, T2),
            result(T6D, B, Outcome.PASSED, 201, T2),
            result(T6D, B, Outcome.INCONCLUSIVE, 201, T5),
            result(T7D, B, Outcome.FAILED, 500, T2),
            result(null, A, Outcome.FAILED, 302, T2),
            result(null, C, Outcome.PASSED, 204, T3));

    private final CoverageCalculator calculator = new CoverageCalculator(OPERATIONS, TESTS, RESULTS);

    private Metric metric(String id) {
        return calculator.metrics().stream().filter(metric -> metric.id().equals(id)).findFirst().orElseThrow();
    }

    private OperationRow row(String key) {
        return calculator.rows().stream().filter(row -> row.operationKey().equals(key)).findFirst().orElseThrow();
    }

    @Nested
    class TestDesign {

        @Test
        void operationsWithAndWithoutTests() {
            // A and B have enabled tests; C has none.
            assertThat(metric("operations").value()).isEqualTo(3);
            assertThat(metric("operationsWithTests")).extracting(Metric::value, Metric::total).containsExactly(2, 3);
            assertThat(metric("operationsWithoutTests")).extracting(Metric::value, Metric::total).containsExactly(1, 3);
            assertThat(calculator.gaps().operationsWithoutTests()).containsExactly(C);
        }

        @Test
        void enabledTestsAreDistinctAndDisabledTestsDoNotCount() {
            // A: t1, t4 (same request as t1), t2, t3 -> 3 distinct; t5 disabled. B: t6, t7, t8 -> 3.
            assertThat(metric("tests").value()).isEqualTo(6);
            assertThat(metric("tests").definition()).contains("(1 duplicate not counted)");
            assertThat(row(A)).extracting(OperationRow::tests, OperationRow::duplicates, OperationRow::inactiveTests)
                    .containsExactly(3, 1, 1);
            assertThat(row(B).tests()).isEqualTo(3);
            assertThat(row(C).tests()).isZero();
        }

        @Test
        void documentedResponsesTargetedByExpectations() {
            // A: 200 (t1), 401 (t2), 400 -> 4XX (t3) = 3 of 3. B: 201 (t6), 400 (t7), 4XX -> 400 (t8) = 2 of 3.
            // C: 0 of 1. Total 5 of 7.
            assertThat(row(A).responsesTargeted()).isEqualTo(3);
            assertThat(row(B).responsesTargeted()).isEqualTo(2);
            assertThat(metric("responsesTargeted")).extracting(Metric::value, Metric::total).containsExactly(5, 7);
        }

        @Test
        void constraintsCoveredByEnabledTestsOfThatScenario() {
            // A: below-minimum (t3) of {below-minimum, at-minimum, type (t5 is disabled)} = 1/3.
            // B: body.missing (t7), body.type (t8) of {missing, type, content-type} = 2/3. C: 0/0.
            assertThat(row(A)).extracting(OperationRow::constraintsCovered, OperationRow::constraints).containsExactly(1, 3);
            assertThat(row(B)).extracting(OperationRow::constraintsCovered, OperationRow::constraints).containsExactly(2, 3);
            assertThat(metric("constraintsCovered")).extracting(Metric::value, Metric::total).containsExactly(3, 6);
        }

        @Test
        void schemaBranchesExercisedByRequestBodies() {
            assertThat(metric("branchesExercised")).extracting(Metric::value, Metric::total).containsExactly(1, 2);
        }

        @Test
        void negativeScenarioKindsPresentAndMissing() {
            // A applies AUTH (present), OUT_OF_RANGE (present), WRONG_TYPE (missing: t5 disabled).
            // B applies MISSING_REQUIRED (present), WRONG_TYPE (present), UNSUPPORTED_CONTENT_TYPE (missing).
            // C applies AUTH (missing). 4 of 7.
            assertThat(metric("negativeKinds")).extracting(Metric::value, Metric::total).containsExactly(4, 7);
            assertThat(row(A).missingNegatives()).containsExactly(ScenarioKind.WRONG_TYPE);
            assertThat(calculator.gaps().missingNegatives()).extracting(gap -> gap.kind(), gap -> gap.operations())
                    .containsExactly(tuple(ScenarioKind.AUTHENTICATION, List.of(C)), tuple(ScenarioKind.WRONG_TYPE, List.of(A)),
                            tuple(ScenarioKind.UNSUPPORTED_CONTENT_TYPE, List.of(B)));
        }
    }

    @Nested
    class Execution {

        @Test
        void latestAttemptPerDistinctTest() {
            // f1: t1 FAILED@T3, its copy t4 PASSED@T4 -> PASSED. f2: ERROR. f3: only skipped -> SKIPPED.
            // f6: INCONCLUSIVE@T5 is newer than PASSED@T2. f7: FAILED. f8: no result -> not run.
            assertThat(calculator.outcomes()).isEqualTo(new OutcomeCounts(6, 4, 1, 1, 1, 1, 1, 1));
            assertThat(row(A).outcomes()).isEqualTo(new OutcomeCounts(3, 2, 1, 0, 1, 0, 1, 0));
        }

        @Test
        void executedAndPassRateAreSeparateFromCoverage() {
            assertThat(metric("executed")).extracting(Metric::value, Metric::total).containsExactly(4, 6);
            assertThat(metric("passRate")).extracting(Metric::value, Metric::total).containsExactly(1, 4);
            assertThat(metric("passRate").group()).isEqualTo(CoverageViews.Group.EXECUTION);
            assertThat(metric("operationsWithTests").group()).isEqualTo(CoverageViews.Group.TEST_DESIGN);
        }

        @Test
        void responsesExercisedByWhatWasReceived() {
            // A: 200, 418 -> 4XX; 302 undocumented; skipped results and the error have no status -> 2 of 3.
            // B: 201, 500 -> default -> 2 of 3. C: 204 (a deleted test's result still counts) -> 1 of 1.
            assertThat(row(A).responsesExercised()).isEqualTo(2);
            assertThat(row(A).undocumentedStatuses()).containsExactly("302");
            assertThat(row(B).responsesExercised()).isEqualTo(2);
            assertThat(row(C).responsesExercised()).isEqualTo(1);
            assertThat(metric("responsesExercised")).extracting(Metric::value, Metric::total).containsExactly(5, 7);
            assertThat(calculator.gaps().responsesNotExercised()).extracting(gap -> gap.operationKey() + " " + gap.status()
                    + (gap.targeted() ? " (targeted)" : "")).containsExactly("GET /items 401 (targeted)", "POST /orders 400 (targeted)");
        }

        @Test
        void summaryHoldsEveryTotal() {
            assertThat(calculator.summary()).isEqualTo(Map.ofEntries(
                    Map.entry("operations", 3), Map.entry("operationsWithTests", 2), Map.entry("tests", 6),
                    Map.entry("duplicates", 1), Map.entry("responses", 7), Map.entry("responsesTargeted", 5),
                    Map.entry("responsesExercised", 5), Map.entry("constraints", 6), Map.entry("constraintsCovered", 3),
                    Map.entry("branches", 2), Map.entry("branchesExercised", 1), Map.entry("executed", 4),
                    Map.entry("passed", 1), Map.entry("failed", 1), Map.entry("error", 1), Map.entry("inconclusive", 1),
                    Map.entry("skipped", 1), Map.entry("notRun", 1), Map.entry("negativeKinds", 7),
                    Map.entry("negativeKindsPresent", 4)));
        }
    }

    @Nested
    class Drilldown {

        @Test
        void operationDetailListsTestsScenariosResponsesBranchesAndNegatives() {
            OperationDetail a = calculator.detail(null, A);
            OperationDetail b = calculator.detail(null, B);

            assertThat(a.tests()).extracting(test -> test.name(), test -> test.duplicateOf(), test -> test.latestOutcome())
                    .containsExactly(tuple("Valid", null, Outcome.FAILED), tuple("No credentials", null, Outcome.ERROR),
                            tuple("limit below", null, Outcome.SKIPPED), tuple("Copy of valid", T1D, Outcome.PASSED));
            assertThat(a.scenarios()).extracting(scenario -> scenario.scenarioKey(), scenario -> scenario.covered())
                    .containsExactly(tuple("happy.minimal", true), tuple("auth.none", true),
                            tuple("query.below-minimum:limit", true), tuple("query.at-minimum:limit", false),
                            tuple("query.type:limit", false));
            assertThat(a.responses()).extracting(response -> response.status(), response -> response.targeted(),
                    response -> response.exercised(), response -> response.receivedStatuses())
                    .containsExactly(tuple("200", true, true, List.of(200)), tuple("401", true, false, List.of()),
                            tuple("4XX", true, true, List.of(418)));
            assertThat(a.negatives()).extracting(negative -> negative.kind(), negative -> negative.state()).containsExactly(
                    tuple(ScenarioKind.AUTHENTICATION, NegativeState.PRESENT),
                    tuple(ScenarioKind.MISSING_REQUIRED, NegativeState.NOT_APPLICABLE),
                    tuple(ScenarioKind.WRONG_TYPE, NegativeState.MISSING),
                    tuple(ScenarioKind.OUT_OF_RANGE, NegativeState.PRESENT),
                    tuple(ScenarioKind.INVALID_VALUE, NegativeState.NOT_APPLICABLE),
                    tuple(ScenarioKind.UNSUPPORTED_CONTENT_TYPE, NegativeState.NOT_APPLICABLE));
            assertThat(b.branches()).extracting(branch -> branch.id(), branch -> branch.exercised())
                    .containsExactly(tuple("delivery#oneOf/0", true), tuple("delivery#oneOf/1", false));
            assertThat(b.skipped()).hasSize(1);
            assertThat(calculator.detail(null, "GET /unknown")).isNull();
        }
    }

    @Nested
    class Rules {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                200 | 200,2XX,default | 200
                201 | 200,2XX,default | 2XX
                503 | 200,2XX,default | default
                503 | 200,4XX         |
                404 | 404,4xx         | 404
                418 | 404,4xx         | 4xx
                """)
        void receivedStatusMatchesExactThenClassThenDefault(int status, String documented, String expected) {
            assertThat(CoverageCalculator.documentedResponse(List.of(documented.split(",")), status)).isEqualTo(expected);
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                400         | 200,400,404,default | 400
                401         | 200,400,404,default | default
                4XX         | 200,400,404,default | 400,404
                4XX         | 200,4XX,422         | 4XX,422
                5XX         | 200,default         | default
                default     | 200,default         | default
                2XX,404     | 200,201,404         | 200,201,404
                """)
        void expectedStatusesTargetDocumentedResponses(String expected, String documented, String targeted) {
            assertThat(CoverageCalculator.targetedResponses(List.of(documented.split(",")), List.of(expected.split(","))))
                    .containsExactly(targeted.split(","));
        }

        @Test
        void anAttemptCountsOverANewerSkip() {
            CoverageCalculator calculator = new CoverageCalculator(List.of(OPERATIONS.getFirst()),
                    List.of(test(T1D, A, "Valid", "happy.minimal", true, "f1", List.of("200"), Set.of(), 0)),
                    List.of(result(T1D, A, Outcome.PASSED, 200, T1), result(T1D, A, Outcome.SKIPPED, null, T5)));

            assertThat(calculator.outcomes()).extracting(OutcomeCounts::executed, OutcomeCounts::passed,
                    OutcomeCounts::skipped).containsExactly(1, 1, 0);
        }

        @Test
        void anEmptyProjectHasZeroDenominators() {
            CoverageCalculator empty = new CoverageCalculator(List.of(), List.of(), List.of());

            assertThat(empty.metrics()).allSatisfy(metric -> {
                assertThat(metric.value()).isZero();
                assertThat(metric.total() == null || metric.total() == 0).isTrue();
            });
            assertThat(empty.gaps().operationsWithoutTests()).isEmpty();
        }
    }
}
