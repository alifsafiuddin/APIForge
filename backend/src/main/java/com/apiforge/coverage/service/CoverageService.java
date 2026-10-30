package com.apiforge.coverage.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.coverage.domain.CoverageSnapshot;
import com.apiforge.coverage.repository.CoverageSnapshotRepository;
import com.apiforge.coverage.service.CoverageCalculator.OperationInput;
import com.apiforge.coverage.service.CoverageCalculator.PlannedScenario;
import com.apiforge.coverage.service.CoverageCalculator.ResultInput;
import com.apiforge.coverage.service.CoverageCalculator.TestInput;
import com.apiforge.coverage.service.CoverageViews.ChangeKind;
import com.apiforge.coverage.service.CoverageViews.CoverageReport;
import com.apiforge.coverage.service.CoverageViews.OperationChange;
import com.apiforge.coverage.service.CoverageViews.OperationDetail;
import com.apiforge.coverage.service.CoverageViews.OperationRow;
import com.apiforge.coverage.service.CoverageViews.SkippedView;
import com.apiforge.coverage.service.CoverageViews.SnapshotView;
import com.apiforge.coverage.service.CoverageViews.VersionChanges;
import com.apiforge.coverage.service.CoverageViews.VersionRef;
import com.apiforge.environment.repository.EnvironmentRepository;
import com.apiforge.execution.repository.CoverageResult;
import com.apiforge.execution.repository.TestResultRepository;
import com.apiforge.generation.engine.DeterministicTestGenerator;
import com.apiforge.generation.engine.DeterministicTestGenerator.Operation;
import com.apiforge.generation.engine.DeterministicTestGenerator.Output;
import com.apiforge.generation.engine.DeterministicTestGenerator.Specification;
import com.apiforge.project.domain.Project;
import com.apiforge.project.repository.ProjectRepository;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.schema.GuardedPattern;
import com.apiforge.schema.MediaTypes;
import com.apiforge.schema.Schema;
import com.apiforge.schema.SchemaResolver;
import com.apiforge.schema.SchemaValidator;
import com.apiforge.spec.domain.ApiOperation;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.repository.ApiOperationRepository;
import com.apiforge.spec.repository.SpecificationVersionRepository;
import com.apiforge.testcase.domain.RequestTemplate;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.domain.TestFingerprint;
import com.apiforge.testcase.repository.TestCaseRepository;

/**
 * Specification coverage of a project's active version (computed on demand, ADR-013) and the
 * snapshots that record it over time (ADR-028). Coverage measures the documented contract only:
 * APIForge has no access to the target's source code, so this is never code coverage.
 */
@Service
public class CoverageService {

    private static final Logger log = LoggerFactory.getLogger(CoverageService.class);
    private static final int HISTORY = 50;
    private static final int CACHED_VERSIONS = 20;

    /** What the generator derives from each operation of a version; versions are immutable. */
    private record Plan(List<PlannedScenario> planned, List<SkippedView> skipped) {
    }

    private final Map<UUID, Map<String, Plan>> plans = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Map<String, Plan>> eldest) {
                    return size() > CACHED_VERSIONS;
                }
            });

    private final ProjectAccessService access;
    private final ProjectRepository projects;
    private final SpecificationVersionRepository versions;
    private final ApiOperationRepository operations;
    private final TestCaseRepository testCases;
    private final TestResultRepository results;
    private final EnvironmentRepository environments;
    private final CoverageSnapshotRepository snapshots;
    private final Clock clock;
    private final DeterministicTestGenerator generator = new DeterministicTestGenerator();

    public CoverageService(ProjectAccessService access, ProjectRepository projects, SpecificationVersionRepository versions,
            ApiOperationRepository operations, TestCaseRepository testCases, TestResultRepository results,
            EnvironmentRepository environments, CoverageSnapshotRepository snapshots, Clock clock) {
        this.access = access;
        this.projects = projects;
        this.versions = versions;
        this.operations = operations;
        this.testCases = testCases;
        this.results = results;
        this.environments = environments;
        this.snapshots = snapshots;
        this.clock = clock;
    }

    /** @param environmentId limit execution metrics to runs against this environment, or {@code null} */
    @Transactional(readOnly = true)
    public CoverageReport report(UUID userId, UUID projectId, UUID environmentId) {
        Project project = access.requireOwned(projectId, userId);
        SpecificationVersion version = activeVersion(project);
        requireEnvironment(projectId, environmentId);
        CoverageCalculator calculator = calculator(project, version, environmentId);
        return new CoverageReport(ref(version), environmentId, calculator.metrics(), calculator.outcomes(),
                calculator.rows(), calculator.gaps());
    }

    @Transactional(readOnly = true)
    public OperationDetail operation(UUID userId, UUID projectId, String operationKey, UUID environmentId) {
        Project project = access.requireOwned(projectId, userId);
        SpecificationVersion version = activeVersion(project);
        requireEnvironment(projectId, environmentId);
        OperationDetail detail = calculator(project, version, environmentId).detail(ref(version), operationKey);
        if (detail == null) {
            throw ApiException.notFound("Operation");
        }
        return detail;
    }

    /** @param fromVersionId the earlier version; by default the version numbered just below the active one */
    @Transactional(readOnly = true)
    public VersionChanges changes(UUID userId, UUID projectId, UUID fromVersionId) {
        Project project = access.requireOwned(projectId, userId);
        SpecificationVersion to = activeVersion(project);
        List<SpecificationVersion> all = versions.findByProjectIdOrderByVersionNumberDesc(projectId);
        SpecificationVersion from;
        if (fromVersionId != null) {
            from = all.stream().filter(version -> version.getId().equals(fromVersionId)).findFirst()
                    .orElseThrow(() -> ApiException.notFound("Specification version"));
            if (from.getId().equals(to.getId())) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "Choose a version other than the active one.");
            }
        } else {
            from = all.stream().filter(version -> version.getVersionNumber() < to.getVersionNumber()).findFirst()
                    .orElse(null);
        }
        CoverageCalculator calculator = calculator(project, to, null);
        Map<UUID, Integer> numbers = new HashMap<>();
        all.forEach(version -> numbers.put(version.getId(), version.getVersionNumber()));
        List<SnapshotView> history = snapshots.findByProjectIdOrderByTakenAtDesc(projectId, PageRequest.of(0, HISTORY))
                .stream().map(snapshot -> view(snapshot, numbers)).toList();
        if (from == null) {
            return new VersionChanges(null, ref(to), List.of(), calculator.rows().size(), null, calculator.summary(),
                    history);
        }
        List<ApiOperation> toOperations = operations.findBySpecificationVersionIdOrderBySortOrderAsc(to.getId());
        SpecificationDiff.Result diff = SpecificationDiff.diff(from.getComponentSchemas(),
                contracts(operations.findBySpecificationVersionIdOrderBySortOrderAsc(from.getId())),
                to.getComponentSchemas(), contracts(toOperations));
        Map<String, OperationRow> rows = new HashMap<>();
        calculator.rows().forEach(row -> rows.put(row.operationKey(), row));
        List<TestCase> active = testCases.findByProjectIdAndLifecycleStatus(projectId, TestCase.LifecycleStatus.ACTIVE);
        List<TestCase> stale = testCases.findByProjectIdAndLifecycleStatus(projectId, TestCase.LifecycleStatus.STALE);
        List<OperationChange> changes = new ArrayList<>();
        for (SpecificationDiff.Change change : diff.changes()) {
            String key = change.operationKey();
            if (change.kind() == ChangeKind.REMOVED) {
                int staleTests = (int) stale.stream().filter(test -> test.getOperationKey().equals(key)).count();
                changes.add(new OperationChange(key, change.kind(), change.aspects(), 0, 0, staleTests));
            } else {
                int earlier = (int) active.stream().filter(test -> test.getOperationKey().equals(key) && test.isExecutable()
                        && !to.getId().equals(test.getSourceSpecificationVersionId())).count();
                OperationRow row = rows.get(key);
                changes.add(new OperationChange(key, change.kind(), change.aspects(), row == null ? 0 : row.tests(), earlier, 0));
            }
        }
        SnapshotView baseline = snapshots.findFirstBySpecificationVersionIdOrderByTakenAtDesc(from.getId())
                .map(snapshot -> view(snapshot, numbers)).orElse(null);
        return new VersionChanges(ref(from), ref(to), changes, diff.unchanged(), baseline, calculator.summary(), history);
    }

    /**
     * Records the summary of the project's active version, unless {@code versionId} is no longer
     * active or nothing changed since the version's last snapshot.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void snapshot(UUID projectId, UUID versionId, CoverageSnapshot.Trigger trigger, UUID runId) {
        Project project = projects.findById(projectId).orElse(null);
        if (project == null || !versionId.equals(project.getActiveSpecificationVersionId())) {
            return;
        }
        SpecificationVersion version = versions.findByIdAndProjectId(versionId, projectId).orElse(null);
        if (version == null) {
            return;
        }
        Map<String, Integer> summary = calculator(project, version, null).summary();
        boolean unchanged = snapshots.findFirstBySpecificationVersionIdOrderByTakenAtDesc(versionId)
                .map(previous -> previous.getMetrics().equals(summary)).orElse(false);
        if (unchanged && trigger != CoverageSnapshot.Trigger.VERSION_ACTIVATED) {
            return;
        }
        snapshots.save(new CoverageSnapshot(projectId, versionId, trigger, runId, Instant.now(clock), summary));
        log.debug("Coverage snapshot recorded for project {} ({})", projectId, trigger);
    }

    // --- Inputs ---

    CoverageCalculator calculator(Project project, SpecificationVersion version, UUID environmentId) {
        List<ApiOperation> versionOperations = operations.findBySpecificationVersionIdOrderBySortOrderAsc(version.getId());
        Map<String, Plan> versionPlans = plans(version, versionOperations);
        SchemaResolver resolver = new SchemaResolver(version.getComponentSchemas());
        SchemaValidator validator = new SchemaValidator(new GuardedPattern());

        List<OperationInput> inputs = new ArrayList<>();
        Map<String, List<SchemaBranches.Branch>> branchesByOperation = new HashMap<>();
        for (ApiOperation operation : versionOperations) {
            List<SchemaBranches.Branch> branches = branches(operation, resolver);
            branchesByOperation.put(operation.getOperationKey(), branches);
            Plan plan = versionPlans.get(operation.getOperationKey());
            inputs.add(new OperationInput(operation.getOperationKey(), operation.getMethod(), operation.getPath(),
                    operation.getTags(), operation.isDeprecated(),
                    operation.getResponses().stream().map(response -> String.valueOf(response.get("status"))).toList(),
                    plan.planned(), plan.skipped(), branches));
        }

        List<TestInput> tests = new ArrayList<>();
        for (TestCase test : testCases.findByProjectIdAndLifecycleStatus(project.getId(), TestCase.LifecycleStatus.ACTIVE)) {
            List<SchemaBranches.Branch> branches = branchesByOperation.get(test.getOperationKey());
            if (branches == null) {
                continue;
            }
            RequestTemplate request = test.getRequest();
            Set<String> hit = !branches.isEmpty() && request.body().mode() == RequestTemplate.BodyMode.JSON
                    ? SchemaBranches.exercised(request.body().value(), branches, validator) : Set.of();
            List<String> statuses = test.getExpectationsValue().statuses();
            tests.add(new TestInput(test.getId(), test.getOperationKey(), test.getName(), test.getScenarioKey(),
                    test.isExecutable(), TestFingerprint.of(test.getOperationKey(), test.getRequestTemplate(), statuses), statuses,
                    hit, test.getSortOrder()));
        }

        List<CoverageResult> found = environmentId == null ? results.findForCoverage(project.getId(), version.getId())
                : results.findForCoverageInEnvironment(project.getId(), version.getId(), environmentId);
        List<ResultInput> resultInputs = found.stream().map(result -> new ResultInput(result.testCaseId(),
                result.operationKey(), result.outcome(), result.responseStatus(), result.executedAt())).toList();
        return new CoverageCalculator(inputs, tests, resultInputs);
    }

    private Map<String, Plan> plans(SpecificationVersion version, List<ApiOperation> versionOperations) {
        Map<String, Plan> cached = plans.get(version.getId());
        if (cached != null) {
            return cached;
        }
        Specification specification = new Specification(version.getComponentSchemas(), version.getSecuritySchemes());
        Map<String, Plan> computed = new HashMap<>();
        for (ApiOperation operation : versionOperations) {
            Output output = generator.generate(specification, new Operation(operation.getMethod(), operation.getPath(),
                    operation.getParameters(), operation.getRequestBody(), operation.getResponses(), operation.getSecurity()));
            computed.put(operation.getOperationKey(), new Plan(
                    output.tests().stream().map(test -> new PlannedScenario(test.scenarioKey(), test.name(),
                            ScenarioKind.of(test.scenarioKey()))).toList(),
                    output.skipped().stream().map(skipped -> new SkippedView(skipped.scenario(), skipped.reason())).toList()));
        }
        Map<String, Plan> frozen = Map.copyOf(computed);
        plans.put(version.getId(), frozen);
        return frozen;
    }

    /** Branches of the request body schema of the first JSON media type (the one generated tests send). */
    private static List<SchemaBranches.Branch> branches(ApiOperation operation, SchemaResolver resolver) {
        Map<String, Object> body = operation.getRequestBody();
        if (body == null || !(body.get("content") instanceof Map<?, ?> content)) {
            return List.of();
        }
        for (Map.Entry<?, ?> entry : content.entrySet()) {
            String mediaType = String.valueOf(entry.getKey());
            if (MediaTypes.isJson(mediaType) && entry.getValue() instanceof Map<?, ?> media && media.get("schema") != null) {
                Schema schema = resolver.resolve(media.get("schema"), "#/paths/" + operation.getPath() + "/"
                        + operation.getMethod().toLowerCase(Locale.ROOT) + "/requestBody/content/" + mediaType + "/schema");
                return SchemaBranches.find(schema);
            }
        }
        return List.of();
    }

    private static List<SpecificationDiff.Contract> contracts(List<ApiOperation> list) {
        return list.stream().map(operation -> new SpecificationDiff.Contract(operation.getOperationKey(),
                operation.getParameters(), operation.getRequestBody(), operation.getResponses(), operation.getSecurity()))
                .toList();
    }

    private SpecificationVersion activeVersion(Project project) {
        if (project.getActiveSpecificationVersionId() == null) {
            throw new ApiException(ErrorCode.NO_ACTIVE_SPECIFICATION,
                    "Import a specification to see its coverage.");
        }
        return versions.findByIdAndProjectId(project.getActiveSpecificationVersionId(), project.getId())
                .orElseThrow(() -> new ApiException(ErrorCode.NO_ACTIVE_SPECIFICATION,
                        "The project has no active specification version."));
    }

    private void requireEnvironment(UUID projectId, UUID environmentId) {
        if (environmentId != null && environments.findByIdAndProjectId(environmentId, projectId).isEmpty()) {
            throw ApiException.notFound("Environment");
        }
    }

    private static VersionRef ref(SpecificationVersion version) {
        return new VersionRef(version.getId(), version.getVersionNumber(), version.getTitle(), version.getApiVersion(),
                version.getImportedAt());
    }

    private static SnapshotView view(CoverageSnapshot snapshot, Map<UUID, Integer> numbers) {
        return new SnapshotView(snapshot.getId(), snapshot.getSpecificationVersionId(),
                numbers.getOrDefault(snapshot.getSpecificationVersionId(), 0), snapshot.getTrigger().name(),
                snapshot.getTestRunId(), snapshot.getTakenAt(), snapshot.getMetrics());
    }
}
