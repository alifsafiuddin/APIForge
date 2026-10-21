package com.apiforge.generation.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.generation.engine.DeterministicTestGenerator;
import com.apiforge.generation.engine.DeterministicTestGenerator.Operation;
import com.apiforge.generation.engine.DeterministicTestGenerator.Output;
import com.apiforge.generation.engine.DeterministicTestGenerator.Skipped;
import com.apiforge.generation.engine.DeterministicTestGenerator.Specification;
import com.apiforge.project.domain.Project;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.repository.ApiOperationRepository;
import com.apiforge.spec.repository.SpecificationVersionRepository;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.domain.TestCase.Definition;
import com.apiforge.testcase.domain.TestSuite;
import com.apiforge.testcase.repository.TestCaseRepository;
import com.apiforge.testcase.repository.TestSuiteRepository;
import com.apiforge.testcase.service.TestSuiteService;

/**
 * Generates deterministic tests from the project's active specification version and stores them.
 *
 * <p>Regeneration is idempotent: a scenario is identified by its operation and scenario key
 * ({@link TestCase#dedupeHash}), so running it again creates nothing new for an unchanged
 * document. Changed scenarios are updated in place (keeping the test id, its suite, enabled flag
 * and history); tests the user edited are never overwritten; generated tests whose scenario no
 * longer applies are archived unless the user edited them.
 */
@Service
public class TestGenerationService {

    public record SkippedView(String operationKey, String scenario, String reason) {

        static SkippedView from(Skipped skipped) {
            return new SkippedView(skipped.operationKey(), skipped.scenario(), skipped.reason());
        }
    }

    /**
     * Outcome of one generation run. {@code keptUserEdits} counts generated tests that the user
     * edited and that were therefore left as they are.
     */
    public record GenerationReport(UUID specificationVersionId, int specificationVersionNumber, int operations,
            int created, int updated, int unchanged, int keptUserEdits, int archived, int total, String generatorVersion,
            List<SkippedView> skipped) {
    }

    public record PlannedScenario(String scenarioKey, String name, TestCase.Category category,
            List<String> expectedStatuses) {
    }

    /** What generation produces for one operation, without storing anything. */
    public record GenerationPlan(String operationKey, UUID specificationVersionId, String generatorVersion,
            List<PlannedScenario> scenarios, List<SkippedView> skipped) {
    }

    private static final int MAX_OPERATION_KEYS = 2000;

    private final ProjectAccessService access;
    private final SpecificationVersionRepository versions;
    private final ApiOperationRepository operations;
    private final TestCaseRepository testCases;
    private final TestSuiteRepository suites;
    private final AuditService audit;
    private final DeterministicTestGenerator generator = new DeterministicTestGenerator();

    public TestGenerationService(ProjectAccessService access, SpecificationVersionRepository versions,
            ApiOperationRepository operations, TestCaseRepository testCases, TestSuiteRepository suites,
            AuditService audit) {
        this.access = access;
        this.versions = versions;
        this.operations = operations;
        this.testCases = testCases;
        this.suites = suites;
        this.audit = audit;
    }

    /** @param operationKeys limit generation to these operations; {@code null} or empty means all */
    @Transactional
    public GenerationReport generate(UUID userId, UUID projectId, List<String> operationKeys) {
        // Locked: concurrent generations (or an activation) for one project run one after another.
        Project project = access.requireOwnedForUpdate(projectId, userId);
        SpecificationVersion version = activeVersion(project);
        List<ApiOperation> selected = select(version, operationKeys);
        Specification specification = new Specification(version.getComponentSchemas(), version.getSecuritySchemes());
        TestSuite suite = generatedSuite(projectId);

        Map<String, TestCase> existing = new HashMap<>();
        for (TestCase test : testCases.findLive(projectId, TestCase.Origin.DETERMINISTIC)) {
            existing.put(test.getDedupeHash(), test);
        }
        Set<String> coveredOperations = new LinkedHashSet<>();
        List<SkippedView> skipped = new ArrayList<>();
        List<TestCase> toSave = new ArrayList<>();
        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int keptUserEdits = 0;
        int total = 0;
        for (ApiOperation operation : selected) {
            coveredOperations.add(operation.getOperationKey());
            Output output = generator.generate(specification, toInput(operation));
            output.skipped().stream().map(SkippedView::from).forEach(skipped::add);
            for (Definition definition : output.tests()) {
                total++;
                TestCase test = existing.remove(definition.dedupeHash());
                if (test == null) {
                    toSave.add(TestCase.generated(projectId, suite.getId(), version.getId(), definition));
                    created++;
                } else if (test.isUserModified()) {
                    keptUserEdits++;
                } else if (test.matches(definition)) {
                    test.confirmSource(version.getId());
                    toSave.add(test);
                    unchanged++;
                } else {
                    test.refresh(definition, version.getId());
                    toSave.add(test);
                    updated++;
                }
            }
        }
        int archived = 0;
        for (TestCase obsolete : existing.values()) {
            if (!coveredOperations.contains(obsolete.getOperationKey())
                    || obsolete.getLifecycleStatus() != TestCase.LifecycleStatus.ACTIVE) {
                continue;
            }
            if (obsolete.isUserModified()) {
                keptUserEdits++;
            } else {
                obsolete.archive();
                toSave.add(obsolete);
                archived++;
            }
        }
        testCases.saveAll(toSave);
        testCases.flush();

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("versionNumber", version.getVersionNumber());
        details.put("operations", selected.size());
        details.put("created", created);
        details.put("updated", updated);
        details.put("archived", archived);
        audit.record(AuditEventType.TESTS_GENERATED, userId, null, projectId, details);
        return new GenerationReport(version.getId(), version.getVersionNumber(), selected.size(), created, updated,
                unchanged, keptUserEdits, archived, total, DeterministicTestGenerator.VERSION, skipped);
    }

    @Transactional(readOnly = true)
    public GenerationPlan plan(UUID userId, UUID projectId, String operationKey) {
        Project project = access.requireOwned(projectId, userId);
        SpecificationVersion version = activeVersion(project);
        ApiOperation operation = select(version, List.of(operationKey)).getFirst();
        Output output = generator.generate(
                new Specification(version.getComponentSchemas(), version.getSecuritySchemes()), toInput(operation));
        List<PlannedScenario> scenarios = output.tests().stream()
                .map(test -> new PlannedScenario(test.scenarioKey(), test.name(), test.category(),
                        test.expectations().statuses()))
                .toList();
        return new GenerationPlan(operationKey, version.getId(), DeterministicTestGenerator.VERSION, scenarios,
                output.skipped().stream().map(SkippedView::from).toList());
    }

    private SpecificationVersion activeVersion(Project project) {
        if (project.getActiveSpecificationVersionId() == null) {
            throw new ApiException(ErrorCode.NO_ACTIVE_SPECIFICATION,
                    "Import a specification before generating tests.");
        }
        return versions.findByIdAndProjectId(project.getActiveSpecificationVersionId(), project.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_SPECIFICATION,
                        "The project has no active specification version."));
    }

    private List<ApiOperation> select(SpecificationVersion version, List<String> operationKeys) {
        List<ApiOperation> all = operations.findBySpecificationVersionIdOrderBySortOrderAsc(version.getId());
        if (operationKeys == null || operationKeys.isEmpty()) {
            return all;
        }
        if (operationKeys.size() > MAX_OPERATION_KEYS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "Select at most %d operations.".formatted(MAX_OPERATION_KEYS));
        }
        Set<String> wanted = new LinkedHashSet<>(operationKeys);
        List<ApiOperation> selected = all.stream().filter(operation -> wanted.contains(operation.getOperationKey()))
                .toList();
        if (selected.size() != wanted.size()) {
            Set<String> unknown = new LinkedHashSet<>(wanted);
            selected.forEach(operation -> unknown.remove(operation.getOperationKey()));
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Unknown operation%s in the active specification: %s."
                    .formatted(unknown.size() == 1 ? "" : "s", String.join(", ", unknown.stream().limit(5).toList())));
        }
        return selected;
    }

    private TestSuite generatedSuite(UUID projectId) {
        return suites.findFirstByProjectIdAndKind(projectId, TestSuite.Kind.GENERATED).orElseGet(() -> {
            String name = TestSuiteService.GENERATED_SUITE_NAME;
            for (int attempt = 2; suites.existsByProjectIdAndNameIgnoreCase(projectId, name); attempt++) {
                name = TestSuiteService.GENERATED_SUITE_NAME + " (" + attempt + ")";
            }
            return suites.saveAndFlush(new TestSuite(projectId, name,
                    "Deterministic tests derived from the active specification.", TestSuite.Kind.GENERATED));
        });
    }

    private static Operation toInput(ApiOperation operation) {
        return new Operation(operation.getMethod(), operation.getPath(), operation.getParameters(),
                operation.getRequestBody(), operation.getResponses(), operation.getSecurity());
    }
}
