package com.apiforge.testcase.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.testcase.domain.TestSuite;
import com.apiforge.testcase.repository.TestCaseRepository;
import com.apiforge.testcase.repository.TestSuiteRepository;
import com.apiforge.testcase.service.TestCaseViews.SuiteView;

/**
 * Suites organize tests. Each project has one GENERATED suite (created on first generation, kept
 * by the system); users add CUSTOM suites and move tests between suites.
 */
@Service
public class TestSuiteService {

    public static final String GENERATED_SUITE_NAME = "Generated tests";

    private final ProjectAccessService access;
    private final TestSuiteRepository suites;
    private final TestCaseRepository testCases;

    public TestSuiteService(ProjectAccessService access, TestSuiteRepository suites, TestCaseRepository testCases) {
        this.access = access;
        this.suites = suites;
        this.testCases = testCases;
    }

    @Transactional(readOnly = true)
    public List<SuiteView> list(UUID userId, UUID projectId) {
        access.requireOwned(projectId, userId);
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : testCases.countBySuite(projectId)) {
            counts.put((UUID) row[0], (Long) row[1]);
        }
        return suites.findByProjectIdOrderByNameAsc(projectId).stream()
                .map(suite -> SuiteView.from(suite, counts.getOrDefault(suite.getId(), 0L)))
                .toList();
    }

    @Transactional
    public SuiteView create(UUID userId, UUID projectId, String name, String description) {
        access.requireOwned(projectId, userId);
        String normalized = normalize(name);
        ensureAvailable(projectId, normalized, null);
        TestSuite suite = save(new TestSuite(projectId, normalized, blankToNull(description), TestSuite.Kind.CUSTOM));
        return SuiteView.from(suite, 0);
    }

    @Transactional
    public SuiteView update(UUID userId, UUID projectId, UUID suiteId, String name, String description,
            long expectedVersion) {
        access.requireOwned(projectId, userId);
        TestSuite suite = require(projectId, suiteId);
        if (suite.getVersion() != expectedVersion) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "The suite was modified since you loaded it. Reload it and try again.");
        }
        String normalized = name == null ? suite.getName() : normalize(name);
        ensureAvailable(projectId, normalized, suite);
        suite.rename(normalized, description == null ? suite.getDescription() : blankToNull(description));
        return SuiteView.from(save(suite), testCases.countLiveInSuite(suiteId));
    }

    /** Only empty custom suites can be deleted, so no test is ever lost by deleting a suite. */
    @Transactional
    public void delete(UUID userId, UUID projectId, UUID suiteId) {
        access.requireOwned(projectId, userId);
        TestSuite suite = require(projectId, suiteId);
        if (suite.getKind() != TestSuite.Kind.CUSTOM) {
            throw new ApiException(ErrorCode.CONFLICT, "The suite of generated tests cannot be deleted.");
        }
        long count = testCases.countLiveInSuite(suiteId);
        if (count > 0) {
            throw new ApiException(ErrorCode.CONFLICT,
                    "The suite still contains %d test%s. Move them to another suite first.".formatted(count,
                            count == 1 ? "" : "s"));
        }
        suites.delete(suite);
    }

    private TestSuite require(UUID projectId, UUID suiteId) {
        return suites.findByIdAndProjectId(suiteId, projectId).orElseThrow(() -> ApiException.notFound("Suite"));
    }

    private void ensureAvailable(UUID projectId, String name, TestSuite current) {
        if (current != null && current.getName().equalsIgnoreCase(name)) {
            return;
        }
        if (suites.existsByProjectIdAndNameIgnoreCase(projectId, name)) {
            throw duplicate(name);
        }
    }

    private TestSuite save(TestSuite suite) {
        try {
            return suites.saveAndFlush(suite);
        } catch (DataIntegrityViolationException ex) {
            throw duplicate(suite.getName());
        }
    }

    private static ApiException duplicate(String name) {
        return new ApiException(ErrorCode.DUPLICATE_NAME, "A suite named '%s' already exists.".formatted(name));
    }

    private static String normalize(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty() || normalized.length() > 100) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Suite name must be 1–100 characters.");
        }
        return normalized;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
