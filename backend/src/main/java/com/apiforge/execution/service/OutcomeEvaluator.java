package com.apiforge.execution.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.apiforge.execution.domain.TestResult.Outcome;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.validation.Assertion;

/**
 * Decides a result (docs/ARCHITECTURE.md section 5) from the test's own expectation (STATUS) and
 * the contract assertions: any FAIL makes it FAILED; otherwise any INCONCLUSIVE makes it
 * INCONCLUSIVE; otherwise PASSED. SKIPPED assertions are listed with their reason and are never
 * counted as passes.
 */
final class OutcomeEvaluator {

    record Evaluation(Outcome outcome, List<Map<String, Object>> checks) {
    }

    private OutcomeEvaluator() {
    }

    static Evaluation evaluate(Expectations expectations, int status, List<Assertion> contract) {
        List<Assertion> assertions = new ArrayList<>();
        String actual = String.valueOf(status);
        String expected = String.join(" or ", expectations.statuses());
        boolean statusMatches = expectations.statuses().stream().anyMatch(candidate -> matches(candidate, actual));
        assertions.add(statusMatches
                ? new Assertion("STATUS", Assertion.Result.PASS, "Status " + actual + " is what the test expects ("
                        + expected + ").", null, null, expected, actual)
                : Assertion.fail("STATUS", "The test expects " + expected + ", but the response is " + actual + ".",
                        expected, actual));
        assertions.addAll(contract);
        Outcome outcome = Outcome.PASSED;
        if (assertions.stream().anyMatch(assertion -> assertion.result() == Assertion.Result.FAIL)) {
            outcome = Outcome.FAILED;
        } else if (assertions.stream().anyMatch(assertion -> assertion.result() == Assertion.Result.INCONCLUSIVE)) {
            outcome = Outcome.INCONCLUSIVE;
        }
        return new Evaluation(outcome, assertions.stream().map(Assertion::toMap).toList());
    }

    /** "404" matches "404"; "4XX" matches any 4xx status; "default" matches anything. */
    static boolean matches(String expected, String actual) {
        String normalized = expected.toUpperCase(Locale.ROOT);
        if (normalized.endsWith("XX")) {
            return actual.length() == 3 && actual.charAt(0) == normalized.charAt(0);
        }
        return normalized.equals("DEFAULT") || normalized.equals(actual);
    }
}
