package com.apiforge.coverage.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import com.apiforge.coverage.service.CoverageViews.BranchView;
import com.apiforge.coverage.service.CoverageViews.Gaps;
import com.apiforge.coverage.service.CoverageViews.Group;
import com.apiforge.coverage.service.CoverageViews.Metric;
import com.apiforge.coverage.service.CoverageViews.NegativeGap;
import com.apiforge.coverage.service.CoverageViews.NegativeState;
import com.apiforge.coverage.service.CoverageViews.NegativeView;
import com.apiforge.coverage.service.CoverageViews.OperationDetail;
import com.apiforge.coverage.service.CoverageViews.OperationRow;
import com.apiforge.coverage.service.CoverageViews.OutcomeCounts;
import com.apiforge.coverage.service.CoverageViews.ResponseGap;
import com.apiforge.coverage.service.CoverageViews.ResponseView;
import com.apiforge.coverage.service.CoverageViews.ScenarioView;
import com.apiforge.coverage.service.CoverageViews.SkippedView;
import com.apiforge.coverage.service.CoverageViews.TestView;
import com.apiforge.coverage.service.CoverageViews.VersionRef;
import com.apiforge.execution.domain.TestResult.Outcome;

/**
 * Computes specification coverage from plain inputs, so every metric can be checked by hand.
 *
 * <p>Definitions (also returned with every metric):
 * <ul>
 * <li>An <b>enabled test</b> is a test case that can run: enabled, approved and active. Disabled,
 * unreviewed, rejected, stale and archived tests cover nothing.</li>
 * <li><b>Duplicates</b> are enabled tests of one operation that send the same request (path,
 * query, headers, body, authentication mode) with the same expected statuses. They are counted
 * once, as a <b>distinct test</b>; all test counts are counts of distinct tests.</li>
 * <li>A distinct test's <b>outcome</b> is the outcome of the most recent execution attempt of any
 * of its copies in test runs on this specification version (optionally of one environment). A
 * test only ever skipped (selected but not sent) is skipped; one without results is not run.</li>
 * </ul>
 */
public final class CoverageCalculator {

    // --- Inputs ---

    public record PlannedScenario(String scenarioKey, String name, ScenarioKind kind) {
    }

    /**
     * @param responses documented response keys in document order ("200", "4XX", "default")
     * @param planned   what the deterministic generator derives from this operation's contract
     * @param skipped   what it considered but cannot generate, with the reason
     */
    public record OperationInput(String operationKey, String method, String path, List<String> tags, boolean deprecated,
            List<String> responses, List<PlannedScenario> planned, List<SkippedView> skipped,
            List<SchemaBranches.Branch> branches) {
    }

    /**
     * @param fingerprint     equal for tests that send the same request with the same expectations
     * @param branchesHit     ids of the operation's schema branches this test's body exercises
     */
    public record TestInput(UUID id, String operationKey, String name, String scenarioKey, boolean enabled,
            String fingerprint, List<String> expectedStatuses, Set<String> branchesHit, int sortOrder) {
    }

    /** A result of a test run on this specification version. */
    public record ResultInput(UUID testCaseId, String operationKey, Outcome outcome, Integer responseStatus,
            Instant executedAt) {
    }

    // --- Summary keys (stable; stored in snapshots) ---

    public static final String OPERATIONS = "operations";
    public static final String OPERATIONS_WITH_TESTS = "operationsWithTests";
    public static final String TESTS = "tests";
    public static final String DUPLICATES = "duplicates";
    public static final String RESPONSES = "responses";
    public static final String RESPONSES_TARGETED = "responsesTargeted";
    public static final String RESPONSES_EXERCISED = "responsesExercised";
    public static final String CONSTRAINTS = "constraints";
    public static final String CONSTRAINTS_COVERED = "constraintsCovered";
    public static final String BRANCHES = "branches";
    public static final String BRANCHES_EXERCISED = "branchesExercised";
    public static final String NEGATIVE_KINDS = "negativeKinds";
    public static final String NEGATIVE_KINDS_PRESENT = "negativeKindsPresent";
    public static final String EXECUTED = "executed";
    public static final String PASSED = "passed";
    public static final String FAILED = "failed";
    public static final String ERROR = "error";
    public static final String INCONCLUSIVE = "inconclusive";
    public static final String SKIPPED = "skipped";
    public static final String NOT_RUN = "notRun";

    private final List<OperationInput> operations;
    private final Map<String, Analysis> byOperation = new LinkedHashMap<>();
    private Map<String, Integer> summary;

    public CoverageCalculator(List<OperationInput> operations, List<TestInput> tests, List<ResultInput> results) {
        this.operations = List.copyOf(operations);
        Map<String, List<TestInput>> testsByOperation = new HashMap<>();
        tests.forEach(test -> testsByOperation.computeIfAbsent(test.operationKey(), key -> new ArrayList<>()).add(test));
        Map<String, List<ResultInput>> resultsByOperation = new HashMap<>();
        results.forEach(result -> resultsByOperation.computeIfAbsent(result.operationKey(), key -> new ArrayList<>()).add(result));
        Map<UUID, List<ResultInput>> resultsByTest = new HashMap<>();
        results.stream().filter(result -> result.testCaseId() != null)
                .forEach(result -> resultsByTest.computeIfAbsent(result.testCaseId(), key -> new ArrayList<>()).add(result));
        for (OperationInput operation : operations) {
            byOperation.put(operation.operationKey(), new Analysis(operation,
                    testsByOperation.getOrDefault(operation.operationKey(), List.of()),
                    resultsByOperation.getOrDefault(operation.operationKey(), List.of()), resultsByTest));
        }
    }

    // --- Per-operation analysis ---

    private static final class Analysis {

        final OperationInput operation;
        final List<TestInput> enabled = new ArrayList<>();
        final int inactive;
        /** Distinct tests: the first enabled test of each fingerprint, in workspace order. */
        final Map<String, TestInput> distinct = new LinkedHashMap<>();
        final Map<UUID, UUID> duplicateOf = new HashMap<>();
        final Map<String, Latest> outcomeByFingerprint = new HashMap<>();
        final Set<String> targeted = new LinkedHashSet<>();
        final Map<String, Set<Integer>> exercised = new LinkedHashMap<>();
        final Set<Integer> undocumented = new TreeSet<>();
        final Map<String, List<UUID>> testsByScenario = new HashMap<>();
        final Set<String> branchesHit = new LinkedHashSet<>();
        final Map<UUID, Latest> latestByTest = new HashMap<>();

        Analysis(OperationInput operation, List<TestInput> tests, List<ResultInput> results,
                Map<UUID, List<ResultInput>> resultsByTest) {
            this.operation = operation;
            List<TestInput> ordered = new ArrayList<>(tests);
            ordered.sort(Comparator.comparingInt(TestInput::sortOrder).thenComparing(TestInput::name)
                    .thenComparing(test -> test.id().toString()));
            int disabled = 0;
            for (TestInput test : ordered) {
                if (!test.enabled()) {
                    disabled++;
                    continue;
                }
                enabled.add(test);
                TestInput first = distinct.putIfAbsent(test.fingerprint(), test);
                if (first != null) {
                    duplicateOf.put(test.id(), first.id());
                }
                targeted.addAll(targetedResponses(operation.responses(), test.expectedStatuses()));
                if (test.scenarioKey() != null) {
                    testsByScenario.computeIfAbsent(test.scenarioKey(), key -> new ArrayList<>()).add(test.id());
                }
                branchesHit.addAll(test.branchesHit());
                Latest latest = Latest.of(resultsByTest.getOrDefault(test.id(), List.of()));
                latestByTest.put(test.id(), latest);
                if (latest != null) {
                    outcomeByFingerprint.merge(test.fingerprint(), latest, Latest::newer);
                }
            }
            this.inactive = disabled;
            for (ResultInput result : results) {
                if (result.outcome() == Outcome.SKIPPED || result.responseStatus() == null) {
                    continue;
                }
                String key = documentedResponse(operation.responses(), result.responseStatus());
                if (key == null) {
                    undocumented.add(result.responseStatus());
                } else {
                    exercised.computeIfAbsent(key, k -> new TreeSet<>()).add(result.responseStatus());
                }
            }
        }

        List<PlannedScenario> constraints() {
            return operation.planned().stream().filter(scenario -> scenario.kind().constraint()).toList();
        }

        int constraintsCovered() {
            return (int) constraints().stream().filter(scenario -> testsByScenario.containsKey(scenario.scenarioKey())).count();
        }

        int branchesExercised() {
            return (int) operation.branches().stream().filter(branch -> branchesHit.contains(branch.id())).count();
        }

        NegativeState negative(ScenarioKind kind) {
            boolean applicable = operation.planned().stream().anyMatch(scenario -> scenario.kind() == kind);
            if (!applicable) {
                return NegativeState.NOT_APPLICABLE;
            }
            boolean present = enabled.stream().anyMatch(test -> ScenarioKind.of(test.scenarioKey()) == kind);
            return present ? NegativeState.PRESENT : NegativeState.MISSING;
        }

        List<ScenarioKind> missingNegatives() {
            return ScenarioKind.NEGATIVE.stream().filter(kind -> negative(kind) == NegativeState.MISSING).toList();
        }

        OutcomeCounts outcomes() {
            int passed = 0;
            int failed = 0;
            int error = 0;
            int inconclusive = 0;
            int skipped = 0;
            int notRun = 0;
            for (String fingerprint : distinct.keySet()) {
                Latest latest = outcomeByFingerprint.get(fingerprint);
                Outcome outcome = latest == null ? null : latest.outcome();
                if (outcome == null) {
                    notRun++;
                    continue;
                }
                switch (outcome) {
                    case PASSED -> passed++;
                    case FAILED -> failed++;
                    case ERROR -> error++;
                    case INCONCLUSIVE -> inconclusive++;
                    case SKIPPED -> skipped++;
                }
            }
            return new OutcomeCounts(distinct.size(), passed + failed + error + inconclusive, passed, failed, error,
                    inconclusive, skipped, notRun);
        }

        OperationRow row() {
            return new OperationRow(operation.operationKey(), operation.method(), operation.path(), operation.tags(),
                    operation.deprecated(), distinct.size(), enabled.size() - distinct.size(), inactive,
                    operation.responses().size(), targeted.size(), exercised.size(), constraints().size(),
                    constraintsCovered(), operation.branches().size(), branchesExercised(), missingNegatives(),
                    undocumented.stream().map(String::valueOf).toList(), outcomes());
        }
    }

    /**
     * The outcome that counts for a test: its most recent attempted execution, or SKIPPED when it
     * was only ever skipped, or {@code null} without results.
     */
    record Latest(Outcome outcome, Integer status, Instant at, boolean attempted) {

        static Latest of(List<ResultInput> results) {
            ResultInput latestAttempt = null;
            ResultInput latestSkip = null;
            for (ResultInput result : results) {
                if (result.outcome() == Outcome.SKIPPED) {
                    latestSkip = later(latestSkip, result);
                } else {
                    latestAttempt = later(latestAttempt, result);
                }
            }
            if (latestAttempt != null) {
                return new Latest(latestAttempt.outcome(), latestAttempt.responseStatus(), latestAttempt.executedAt(), true);
            }
            return latestSkip == null ? null : new Latest(Outcome.SKIPPED, null, latestSkip.executedAt(), false);
        }

        private static ResultInput later(ResultInput current, ResultInput candidate) {
            return current == null || candidate.executedAt().isAfter(current.executedAt()) ? candidate : current;
        }

        /** For duplicates: an attempt beats a skip, then the more recent one wins. */
        static Latest newer(Latest first, Latest second) {
            if (first == null) {
                return second;
            }
            if (second == null) {
                return first;
            }
            if (first.attempted() != second.attempted()) {
                return first.attempted() ? first : second;
            }
            return second.at().isAfter(first.at()) ? second : first;
        }
    }

    // --- Documented responses ---

    /** The documented response a received status counts towards: exact code, then class, then default. */
    static String documentedResponse(List<String> documented, int status) {
        String code = String.valueOf(status);
        for (String candidate : List.of(code, code.charAt(0) + "XX", "DEFAULT")) {
            for (String key : documented) {
                if (key.toUpperCase(Locale.ROOT).equals(candidate)) {
                    return key;
                }
            }
        }
        return null;
    }

    /**
     * The documented responses a test aims at with its expected statuses: a code counts towards the
     * response it would match when received; a class ({@code 4XX}) towards the documented class and
     * every documented code in it (or {@code default} when there is none); {@code default} only
     * towards {@code default}.
     */
    static Set<String> targetedResponses(List<String> documented, List<String> expected) {
        Set<String> targeted = new LinkedHashSet<>();
        for (String status : expected) {
            String upper = status.toUpperCase(Locale.ROOT);
            if (upper.matches("\\d{3}")) {
                String key = documentedResponse(documented, Integer.parseInt(upper));
                if (key != null) {
                    targeted.add(key);
                }
            } else if (upper.matches("\\dXX")) {
                List<String> inClass = documented.stream().filter(key -> {
                    String documentedUpper = key.toUpperCase(Locale.ROOT);
                    return documentedUpper.equals(upper) || documentedUpper.matches(upper.charAt(0) + "\\d{2}");
                }).toList();
                if (inClass.isEmpty()) {
                    documented.stream().filter(key -> key.equalsIgnoreCase("default")).forEach(targeted::add);
                } else {
                    targeted.addAll(inClass);
                }
            } else if (upper.equals("DEFAULT")) {
                documented.stream().filter(key -> key.equalsIgnoreCase("default")).forEach(targeted::add);
            }
        }
        return targeted;
    }

    // --- Report ---

    public List<OperationRow> rows() {
        return byOperation.values().stream().map(Analysis::row).toList();
    }

    /** Totals over all operations; the keys are the constants above. */
    public Map<String, Integer> summary() {
        if (summary == null) {
            summary = computeSummary();
        }
        return summary;
    }

    private Map<String, Integer> computeSummary() {
        Map<String, Integer> totals = new LinkedHashMap<>();
        int withTests = 0;
        int negativeKinds = 0;
        int negativePresent = 0;
        int[] sums = new int[16];
        for (Analysis analysis : byOperation.values()) {
            OperationRow row = analysis.row();
            if (row.tests() > 0) {
                withTests++;
            }
            for (ScenarioKind kind : ScenarioKind.NEGATIVE) {
                NegativeState state = analysis.negative(kind);
                if (state != NegativeState.NOT_APPLICABLE) {
                    negativeKinds++;
                    if (state == NegativeState.PRESENT) {
                        negativePresent++;
                    }
                }
            }
            OutcomeCounts outcomes = row.outcomes();
            int[] values = {row.tests(), row.duplicates(), row.responses(), row.responsesTargeted(), row.responsesExercised(),
                    row.constraints(), row.constraintsCovered(), row.branches(), row.branchesExercised(), outcomes.executed(),
                    outcomes.passed(), outcomes.failed(), outcomes.error(), outcomes.inconclusive(), outcomes.skipped(),
                    outcomes.notRun()};
            for (int index = 0; index < values.length; index++) {
                sums[index] += values[index];
            }
        }
        totals.put(OPERATIONS, byOperation.size());
        totals.put(OPERATIONS_WITH_TESTS, withTests);
        String[] keys = {TESTS, DUPLICATES, RESPONSES, RESPONSES_TARGETED, RESPONSES_EXERCISED, CONSTRAINTS,
                CONSTRAINTS_COVERED, BRANCHES, BRANCHES_EXERCISED, EXECUTED, PASSED, FAILED, ERROR, INCONCLUSIVE, SKIPPED,
                NOT_RUN};
        for (int index = 0; index < keys.length; index++) {
            totals.put(keys[index], sums[index]);
        }
        totals.put(NEGATIVE_KINDS, negativeKinds);
        totals.put(NEGATIVE_KINDS_PRESENT, negativePresent);
        return Collections.unmodifiableMap(totals);
    }

    public OutcomeCounts outcomes() {
        Map<String, Integer> s = summary();
        return new OutcomeCounts(s.get(TESTS), s.get(EXECUTED), s.get(PASSED), s.get(FAILED), s.get(ERROR),
                s.get(INCONCLUSIVE), s.get(SKIPPED), s.get(NOT_RUN));
    }

    public List<Metric> metrics() {
        Map<String, Integer> s = summary();
        int operationsCount = s.get(OPERATIONS);
        List<Metric> metrics = new ArrayList<>();
        metrics.add(new Metric("operations", Group.TEST_DESIGN, "Discovered operations", operationsCount, null,
                "Operations (HTTP method and path) in the active specification version."));
        metrics.add(new Metric("operationsWithTests", Group.TEST_DESIGN, "Operations with tests",
                s.get(OPERATIONS_WITH_TESTS), operationsCount,
                "Operations with at least one enabled test (enabled, approved and active). Disabled, unreviewed and "
                        + "stale tests do not count."));
        metrics.add(new Metric("operationsWithoutTests", Group.TEST_DESIGN, "Operations without tests",
                operationsCount - s.get(OPERATIONS_WITH_TESTS), operationsCount,
                "Operations with no enabled test: nothing checks them. Listed under Endpoints without tests."));
        metrics.add(new Metric("tests", Group.TEST_DESIGN, "Enabled tests", s.get(TESTS), null,
                "Distinct enabled tests. Tests of one operation that send the same request with the same expected "
                        + "statuses are counted once (" + s.get(DUPLICATES) + " duplicate" + (s.get(DUPLICATES) == 1 ? "" : "s")
                        + " not counted)."));
        metrics.add(new Metric("responsesTargeted", Group.TEST_DESIGN, "Documented responses targeted",
                s.get(RESPONSES_TARGETED), s.get(RESPONSES),
                "Documented responses (each status code, class such as 4XX, or default of each operation) that an "
                        + "enabled test expects."));
        metrics.add(new Metric("constraintsCovered", Group.TEST_DESIGN, "Request constraints covered",
                s.get(CONSTRAINTS_COVERED), s.get(CONSTRAINTS),
                "Constraints of the request contract that APIForge can test one at a time (required inputs, types, "
                        + "minimum/maximum, lengths, item counts, enums, content type, and their accepted boundary "
                        + "values), as derived by the test generator, with an enabled test for exactly that check. "
                        + "Scenarios the generator cannot build are listed per operation and not counted."));
        metrics.add(new Metric("branchesExercised", Group.TEST_DESIGN, "Schema branches exercised",
                s.get(BRANCHES_EXERCISED), s.get(BRANCHES),
                "Alternatives of oneOf/anyOf in request bodies for which an enabled test sends a value that validates "
                        + "against that alternative."));
        metrics.add(new Metric("negativeKinds", Group.TEST_DESIGN, "Negative scenario kinds present",
                s.get(NEGATIVE_KINDS_PRESENT), s.get(NEGATIVE_KINDS),
                "Per operation, the kinds of invalid request that apply (credentials, missing required input, wrong "
                        + "type, out-of-range value, value outside an enum, unsupported content type) and have at least "
                        + "one enabled test."));
        metrics.add(new Metric("executed", Group.EXECUTION, "Tests executed", s.get(EXECUTED), s.get(TESTS),
                "Distinct enabled tests with at least one execution attempt in a test run on this specification "
                        + "version. Skipped tests (selected but not sent) are not executed."));
        metrics.add(new Metric("passRate", Group.EXECUTION, "Passed of executed", s.get(PASSED), s.get(EXECUTED),
                "Executed tests whose latest attempt passed. Failed, errored and inconclusive attempts are not passes. "
                        + "This is an execution result, not coverage."));
        metrics.add(new Metric("responsesExercised", Group.EXECUTION, "Documented responses exercised",
                s.get(RESPONSES_EXERCISED), s.get(RESPONSES),
                "Documented responses that a test run on this version actually received (by the status, matched "
                        + "exactly, then by class, then default), whatever the test expected."));
        return metrics;
    }

    public Gaps gaps() {
        List<String> untested = new ArrayList<>();
        List<ResponseGap> responses = new ArrayList<>();
        Map<ScenarioKind, List<String>> negatives = new LinkedHashMap<>();
        ScenarioKind.NEGATIVE.forEach(kind -> negatives.put(kind, new ArrayList<>()));
        for (Analysis analysis : byOperation.values()) {
            String key = analysis.operation.operationKey();
            if (analysis.distinct.isEmpty()) {
                untested.add(key);
            }
            for (String status : analysis.operation.responses()) {
                if (!analysis.exercised.containsKey(status)) {
                    responses.add(new ResponseGap(key, status, analysis.targeted.contains(status)));
                }
            }
            analysis.missingNegatives().forEach(kind -> negatives.get(kind).add(key));
        }
        List<NegativeGap> missing = negatives.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> new NegativeGap(entry.getKey(), entry.getKey().label(), List.copyOf(entry.getValue())))
                .toList();
        return new Gaps(untested, responses, missing);
    }

    public OperationDetail detail(VersionRef version, String operationKey) {
        Analysis analysis = byOperation.get(operationKey);
        if (analysis == null) {
            return null;
        }
        List<TestView> tests = new ArrayList<>();
        for (TestInput test : analysis.enabled) {
            Latest latest = analysis.latestByTest.get(test.id());
            tests.add(new TestView(test.id(), test.name(), test.scenarioKey(), ScenarioKind.of(test.scenarioKey()),
                    analysis.duplicateOf.get(test.id()), latest == null ? null : latest.outcome(),
                    latest == null ? null : latest.status(), latest == null ? null : latest.at()));
        }
        List<ScenarioView> scenarios = analysis.operation.planned().stream()
                .map(scenario -> new ScenarioView(scenario.scenarioKey(), scenario.name(), scenario.kind(),
                        analysis.testsByScenario.containsKey(scenario.scenarioKey()),
                        analysis.testsByScenario.getOrDefault(scenario.scenarioKey(), List.of())))
                .toList();
        List<ResponseView> responses = analysis.operation.responses().stream()
                .map(status -> new ResponseView(status, analysis.targeted.contains(status), analysis.exercised.containsKey(status),
                        List.copyOf(analysis.exercised.getOrDefault(status, Set.of()))))
                .toList();
        List<BranchView> branches = analysis.operation.branches().stream()
                .map(branch -> new BranchView(branch.id(), branch.path(), branch.label(), analysis.branchesHit.contains(branch.id())))
                .toList();
        List<NegativeView> negatives = ScenarioKind.NEGATIVE.stream()
                .map(kind -> new NegativeView(kind, kind.label(), analysis.negative(kind))).toList();
        return new OperationDetail(version, analysis.row(), tests, scenarios, analysis.operation.skipped(), responses,
                branches, negatives);
    }

    public List<OperationInput> operations() {
        return operations;
    }
}
