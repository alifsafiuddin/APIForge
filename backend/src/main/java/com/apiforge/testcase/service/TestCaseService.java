package com.apiforge.testcase.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.web.Pagination;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.repository.TestCaseRepository;
import com.apiforge.testcase.repository.TestSuiteRepository;
import com.apiforge.testcase.service.TestCaseViews.OperationTestCount;
import com.apiforge.testcase.service.TestCaseViews.TestCaseDetail;
import com.apiforge.testcase.service.TestCaseViews.TestCaseSummary;
import com.apiforge.testcase.service.TestCaseViews.TestSummary;
import com.apiforge.testcase.service.TestDefinitionValidator.ExpectationsInput;
import com.apiforge.testcase.service.TestDefinitionValidator.RequestInput;

/**
 * Review and curation of a project's tests: listing, editing, enabling and disabling, moving
 * between suites and review decisions. Every operation goes through the owned project.
 */
@Service
public class TestCaseService {

    /** List filters; {@code null} means "any". Archived tests are only listed when asked for. */
    public record TestFilter(String operationKey, UUID suiteId, TestCase.Category category, TestCase.Origin origin,
            Boolean enabled, TestCase.ReviewStatus reviewStatus, TestCase.LifecycleStatus lifecycleStatus, String query) {
    }

    public record EditTest(String name, RequestInput request, ExpectationsInput expectations, String rationale,
            long expectedVersion) {
    }

    /** Null fields are left unchanged. */
    public record ChangeTest(Boolean enabled, UUID suiteId, long expectedVersion) {
    }

    public record BulkChange(List<UUID> testIds, Boolean enabled, UUID suiteId) {
    }

    public enum ReviewDecision {
        APPROVE, REJECT
    }

    static final int MAX_BULK = 500;

    private static final Map<String, String> SORTABLE = Map.of("name", "name", "operationKey", "operationKey",
            "category", "category", "sortOrder", "sortOrder", "updatedAt", "updatedAt", "createdAt", "createdAt");
    private static final Sort DEFAULT_SORT = Sort.by("operationKey", "sortOrder", "name");

    private final ProjectAccessService access;
    private final TestCaseRepository testCases;
    private final TestSuiteRepository suites;
    private final Clock clock;

    public TestCaseService(ProjectAccessService access, TestCaseRepository testCases, TestSuiteRepository suites,
            Clock clock) {
        this.access = access;
        this.testCases = testCases;
        this.suites = suites;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Page<TestCaseSummary> list(UUID userId, UUID projectId, TestFilter filter, Pageable pageable) {
        access.requireOwned(projectId, userId);
        Pageable page = Pagination.sanitize(pageable, SORTABLE, DEFAULT_SORT);
        return testCases.findAll(matching(projectId, filter), page).map(TestCaseSummary::from);
    }

    @Transactional(readOnly = true)
    public TestSummary summary(UUID userId, UUID projectId) {
        access.requireOwned(projectId, userId);
        List<OperationTestCount> operations = testCases.countByOperation(projectId).stream()
                .map(count -> new OperationTestCount(count.operationKey(), count.total(), count.enabled(), count.stale()))
                .toList();
        return new TestSummary(operations.stream().mapToLong(OperationTestCount::total).sum(),
                operations.stream().mapToLong(OperationTestCount::enabled).sum(),
                operations.stream().mapToLong(OperationTestCount::stale).sum(), operations);
    }

    @Transactional(readOnly = true)
    public TestCaseDetail get(UUID userId, UUID projectId, UUID testId) {
        access.requireOwned(projectId, userId);
        return TestCaseDetail.from(require(projectId, testId));
    }

    /** Replaces the test's request and expectations; the test is then marked as edited by the user. */
    @Transactional
    public TestCaseDetail edit(UUID userId, UUID projectId, UUID testId, EditTest command) {
        access.requireOwned(projectId, userId);
        TestCase test = requireCurrent(projectId, testId, command.expectedVersion());
        String name = command.name() == null ? "" : command.name().strip();
        if (name.isEmpty() || name.length() > 200) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Test name must be 1–200 characters.");
        }
        String rationale = command.rationale() == null || command.rationale().isBlank() ? null : command.rationale().strip();
        TestDefinitionValidator.Validated validated = TestDefinitionValidator.validate(test.getOperationKey(),
                command.request(), command.expectations(), test.getExpectationsValue().responseChecks());
        test.edit(name, validated.request(), validated.expectations(), rationale);
        return TestCaseDetail.from(testCases.saveAndFlush(test));
    }

    @Transactional
    public TestCaseDetail change(UUID userId, UUID projectId, UUID testId, ChangeTest command) {
        access.requireOwned(projectId, userId);
        TestCase test = requireCurrent(projectId, testId, command.expectedVersion());
        apply(test, projectId, command.enabled(), command.suiteId());
        return TestCaseDetail.from(testCases.saveAndFlush(test));
    }

    /** Enables, disables or moves many tests at once; all ids must belong to the project. */
    @Transactional
    public int bulkChange(UUID userId, UUID projectId, BulkChange command) {
        access.requireOwned(projectId, userId);
        Set<UUID> ids = new LinkedHashSet<>(command.testIds() == null ? List.of() : command.testIds());
        if (ids.isEmpty() || ids.size() > MAX_BULK) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Select between 1 and %d tests.".formatted(MAX_BULK));
        }
        if (command.enabled() == null && command.suiteId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Nothing to change: set 'enabled' or 'suiteId'.");
        }
        List<TestCase> found = testCases.findByProjectIdAndIdIn(projectId, ids).stream()
                .filter(test -> test.getLifecycleStatus() != TestCase.LifecycleStatus.ARCHIVED).toList();
        if (found.size() != ids.size()) {
            throw ApiException.notFound("Test");
        }
        found.forEach(test -> apply(test, projectId, command.enabled(), command.suiteId()));
        testCases.saveAllAndFlush(found);
        return found.size();
    }

    @Transactional
    public TestCaseDetail review(UUID userId, UUID projectId, UUID testId, ReviewDecision decision) {
        access.requireOwned(projectId, userId);
        TestCase test = require(projectId, testId);
        test.review(decision == ReviewDecision.APPROVE ? TestCase.ReviewStatus.APPROVED : TestCase.ReviewStatus.REJECTED,
                userId, Instant.now(clock));
        return TestCaseDetail.from(testCases.saveAndFlush(test));
    }

    private void apply(TestCase test, UUID projectId, Boolean enabled, UUID suiteId) {
        if (enabled != null) {
            test.setEnabled(enabled);
        }
        if (suiteId != null) {
            suites.findByIdAndProjectId(suiteId, projectId).orElseThrow(() -> ApiException.notFound("Suite"));
            test.moveTo(suiteId);
        }
    }

    private TestCase require(UUID projectId, UUID testId) {
        return testCases.findByIdAndProjectId(testId, projectId)
                .filter(test -> test.getLifecycleStatus() != TestCase.LifecycleStatus.ARCHIVED)
                .orElseThrow(() -> ApiException.notFound("Test"));
    }

    private TestCase requireCurrent(UUID projectId, UUID testId, long expectedVersion) {
        TestCase test = require(projectId, testId);
        if (test.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "The test was modified since you loaded it. Reload it and try again.");
        }
        return test;
    }

    private static Specification<TestCase> matching(UUID projectId, TestFilter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            predicates.add(builder.equal(root.get("projectId"), projectId));
            if (filter.lifecycleStatus() != null) {
                predicates.add(builder.equal(root.get("lifecycleStatus"), filter.lifecycleStatus()));
            } else {
                predicates.add(builder.notEqual(root.get("lifecycleStatus"), TestCase.LifecycleStatus.ARCHIVED));
            }
            if (filter.operationKey() != null) {
                predicates.add(builder.equal(root.get("operationKey"), filter.operationKey()));
            }
            if (filter.suiteId() != null) {
                predicates.add(builder.equal(root.get("suiteId"), filter.suiteId()));
            }
            if (filter.category() != null) {
                predicates.add(builder.equal(root.get("category"), filter.category()));
            }
            if (filter.origin() != null) {
                predicates.add(builder.equal(root.get("origin"), filter.origin()));
            }
            if (filter.enabled() != null) {
                predicates.add(builder.equal(root.get("enabled"), filter.enabled()));
            }
            if (filter.reviewStatus() != null) {
                predicates.add(builder.equal(root.get("reviewStatus"), filter.reviewStatus()));
            }
            String pattern = likePattern(filter.query());
            if (pattern != null) {
                predicates.add(builder.like(builder.lower(root.get("name")), pattern, '\\'));
            }
            return builder.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String likePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        return "%" + query.strip().toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                + "%";
    }
}
