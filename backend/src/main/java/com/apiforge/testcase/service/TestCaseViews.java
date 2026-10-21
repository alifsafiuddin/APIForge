package com.apiforge.testcase.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.domain.TestSuite;

/** API representations of test cases and suites. */
public final class TestCaseViews {

    private TestCaseViews() {
    }

    /** A row of the test list. */
    public record TestCaseSummary(UUID id, UUID suiteId, String operationKey, String method, String path, String name,
            TestCase.Category category, TestCase.Origin origin, TestCase.ReviewStatus reviewStatus,
            TestCase.LifecycleStatus lifecycleStatus, TestCase.RiskLevel riskLevel, boolean enabled,
            boolean userModified, List<String> expectedStatuses, int sortOrder, Instant updatedAt, long version) {

        static TestCaseSummary from(TestCase test) {
            String[] parts = split(test.getOperationKey());
            return new TestCaseSummary(test.getId(), test.getSuiteId(), test.getOperationKey(), parts[0], parts[1],
                    test.getName(), test.getCategory(), test.getOrigin(), test.getReviewStatus(),
                    test.getLifecycleStatus(), test.getRiskLevel(), test.isEnabled(), test.isUserModified(),
                    test.getExpectationsValue().statuses(), test.getSortOrder(), test.getUpdatedAt(), test.getVersion());
        }
    }

    /**
     * Everything about one test, including why it exists ({@code rationale}, {@code specLocation},
     * {@code scenarioKey}) and where it came from ({@code origin}, {@code generatorVersion},
     * {@code sourceSpecificationVersionId}).
     */
    public record TestCaseDetail(UUID id, UUID suiteId, String operationKey, String method, String path, String name,
            TestCase.Category category, TestCase.Origin origin, TestCase.ReviewStatus reviewStatus,
            TestCase.LifecycleStatus lifecycleStatus, TestCase.RiskLevel riskLevel, boolean enabled,
            boolean userModified, boolean executable, String scenarioKey, String specLocation, String rationale,
            String generatorVersion, UUID sourceSpecificationVersionId, Map<String, Object> request,
            Map<String, Object> expectations, UUID reviewedBy, Instant reviewedAt, Instant createdAt, Instant updatedAt,
            long version) {

        static TestCaseDetail from(TestCase test) {
            String[] parts = split(test.getOperationKey());
            return new TestCaseDetail(test.getId(), test.getSuiteId(), test.getOperationKey(), parts[0], parts[1],
                    test.getName(), test.getCategory(), test.getOrigin(), test.getReviewStatus(),
                    test.getLifecycleStatus(), test.getRiskLevel(), test.isEnabled(), test.isUserModified(),
                    test.isExecutable(), test.getScenarioKey(), test.getSpecLocation(), test.getRationale(),
                    test.getGeneratorVersion(), test.getSourceSpecificationVersionId(), test.getRequest().toMap(),
                    test.getExpectationsValue().toMap(), test.getReviewedBy(), test.getReviewedAt(),
                    test.getCreatedAt(), test.getUpdatedAt(), test.getVersion());
        }
    }

    public record SuiteView(UUID id, String name, String description, TestSuite.Kind kind, long testCount,
            Instant createdAt, Instant updatedAt, long version) {

        static SuiteView from(TestSuite suite, long testCount) {
            return new SuiteView(suite.getId(), suite.getName(), suite.getDescription(), suite.getKind(), testCount,
                    suite.getCreatedAt(), suite.getUpdatedAt(), suite.getVersion());
        }
    }

    /** Test counts for one operation key (archived tests excluded). */
    public record OperationTestCount(String operationKey, long total, long enabled, long stale) {
    }

    public record TestSummary(long total, long enabled, long stale, List<OperationTestCount> operations) {
    }

    /** "GET /pets/{id}" → ["GET", "/pets/{id}"]. */
    static String[] split(String operationKey) {
        int space = operationKey.indexOf(' ');
        return space < 0 ? new String[] {operationKey, ""}
                : new String[] {operationKey.substring(0, space), operationKey.substring(space + 1)};
    }
}
