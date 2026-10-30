package com.apiforge.execution.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.environment.service.EnvironmentService;
import com.apiforge.environment.service.EnvironmentViews.ExecutionEnvironment;
import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.repository.TestRunRepository;
import com.apiforge.execution.service.ExecutionHistoryViews.ResultDetail;
import com.apiforge.execution.service.ExecutionHistoryViews.RunView;
import com.apiforge.outbound.Cancellation;
import com.apiforge.outbound.OutboundProperties;
import com.apiforge.project.domain.Project;
import com.apiforge.project.service.ProjectAccessService;
import com.apiforge.spec.domain.SpecificationVersion;
import com.apiforge.spec.repository.ApiOperationRepository;
import com.apiforge.spec.repository.SpecificationVersionRepository;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.TestCase;
import com.apiforge.testcase.repository.TestCaseRepository;
import com.apiforge.testcase.service.TestDefinitionValidator;
import com.apiforge.testcase.service.TestDefinitionValidator.ExpectationsInput;
import com.apiforge.testcase.service.TestDefinitionValidator.RequestInput;
import com.apiforge.validation.ContractValidator;
import com.apiforge.validation.ContractValidator.DocumentedOperation;

/**
 * Starts test runs, sends ad hoc requests and cancels runs. Everything a run needs (tests,
 * environment, decrypted secrets) is copied into an immutable plan before it is queued.
 */
@Service
public class ExecutionService {

    /**
     * Which tests to run. {@code testIds} selects tests explicitly (all must be runnable); otherwise
     * every runnable test, optionally narrowed to one operation or one suite.
     */
    public record RunRequest(UUID environmentId, List<UUID> testIds, String operationKey, UUID suiteId) {
    }

    /**
     * One request to {@code path} (a concrete path, no templates) on the environment's base URL.
     * {@code expectedStatuses} defaults to 2XX.
     */
    public record AdHocRequest(UUID environmentId, String method, String path, RequestInput request,
            List<String> expectedStatuses) {
    }

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private static final Pattern PATH = Pattern.compile("^/[^\\s?#{}\\\\]{0,2000}$");

    private final ProjectAccessService access;
    private final EnvironmentService environments;
    private final TestCaseRepository testCases;
    private final TestRunRepository runs;
    private final RunRecorder recorder;
    private final RunExecutor executor;
    private final AuditService audit;
    private final OutboundProperties properties;
    private final SpecificationVersionRepository versions;
    private final ApiOperationRepository operations;

    public ExecutionService(ProjectAccessService access, EnvironmentService environments, TestCaseRepository testCases,
            TestRunRepository runs, RunRecorder recorder, RunExecutor executor, AuditService audit,
            OutboundProperties properties, SpecificationVersionRepository versions, ApiOperationRepository operations) {
        this.versions = versions;
        this.operations = operations;
        this.access = access;
        this.environments = environments;
        this.testCases = testCases;
        this.runs = runs;
        this.recorder = recorder;
        this.executor = executor;
        this.audit = audit;
        this.properties = properties;
    }

    public RunView startRun(UUID userId, UUID projectId, RunRequest request) {
        Project project = access.requireOwned(projectId, userId);
        if (request.environmentId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Choose an environment to run the tests in.");
        }
        ExecutionEnvironment environment = environments.forExecution(projectId, request.environmentId());
        List<TestCase> selected = select(projectId, request);
        if (selected.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "No runnable tests match the selection (tests must be enabled, approved and active).");
        }
        if (selected.size() > properties.maxTestsPerRun()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "A run can contain at most %d tests; %d were selected."
                    .formatted(properties.maxTestsPerRun(), selected.size()));
        }
        reserveSlot(userId);
        try {
            TestRun run = recorder.create(TestRun.started(TestRun.Kind.TEST_RUN, projectId, environment.id(),
                    environment.name(), environment.baseUrl(), request.suiteId(),
                    project.getActiveSpecificationVersionId(), userId, selected.size()));
            List<RunExecutor.Item> items = selected.stream().map(ExecutionService::item).toList();
            audit.record(AuditEventType.RUN_STARTED, userId, null, projectId,
                    Map.of("runId", run.getId().toString(), "tests", items.size(), "environment", environment.name()));
            executor.submit(new RunExecutor.Plan(run.getId(), userId, projectId, environment,
                    contract(project.getActiveSpecificationVersionId()), items));
            return RunView.from(run);
        } catch (RuntimeException ex) {
            executor.release(userId);
            throw ex;
        }
    }

    /** Sends one request synchronously and returns its stored (redacted) result. */
    public ResultDetail adHoc(UUID userId, UUID projectId, AdHocRequest request) {
        Project project = access.requireOwned(projectId, userId);
        String method = request.method() == null ? "" : request.method().strip().toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Method must be one of " + String.join(", ", METHODS) + ".");
        }
        String path = request.path() == null ? "" : request.path().strip();
        if (!PATH.matcher(path).matches() || path.startsWith("//")) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Path must start with '/' and contain no spaces, query "
                    + "string, fragment, braces or backslashes; put query parameters in 'query'.");
        }
        if (request.environmentId() == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Choose an environment.");
        }
        List<String> statuses = request.expectedStatuses() == null || request.expectedStatuses().isEmpty()
                ? List.of("2XX") : request.expectedStatuses();
        TestDefinitionValidator.Validated validated = TestDefinitionValidator.validate(method + " " + path,
                request.request(), new ExpectationsInput(statuses, "Ad hoc request.", true), List.of());
        ExecutionEnvironment environment = environments.forExecution(projectId, request.environmentId());
        TestCase.RiskLevel risk = TestCase.RiskLevel.forMethod(method);
        if (!RunExecutor.allows(environment.executionPolicy(), risk)) {
            throw new ApiException(ErrorCode.EXECUTION_POLICY, "Environment '%s' has policy %s, which does not allow %s %s requests."
                    .formatted(environment.name(), environment.executionPolicy(), risk, method));
        }
        long retryAfter = executor.tryAcquireRequest(userId);
        if (retryAfter > 0) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Request limit reached; try again in %d s.".formatted(retryAfter));
        }
        reserveSlot(userId);
        try {
            TestRun run = recorder.create(TestRun.started(TestRun.Kind.AD_HOC, projectId, environment.id(),
                    environment.name(), environment.baseUrl(), null, project.getActiveSpecificationVersionId(), userId, 1));
            recorder.start(run.getId());
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("name", "Ad hoc " + method + " " + path);
            snapshot.put("expectations", validated.expectations().toMap());
            RunExecutor.Item item = new RunExecutor.Item(null, method + " " + path, method, path, risk, snapshot,
                    validated.request(), validated.expectations());
            TestResult result = recorder.record(run.getId(), executor.execute(run.getId(), environment,
                    contract(project.getActiveSpecificationVersionId()), item, new Cancellation()));
            if (result.getErrorKind() == TestResult.ErrorKind.BLOCKED_BY_POLICY) {
                recorder.auditBlocked(userId, projectId, run.getId(), result.getErrorMessage());
            }
            recorder.finish(run.getId(), TestRun.Status.COMPLETED, null);
            return ResultDetail.from(result);
        } finally {
            executor.release(userId);
        }
    }

    public RunView cancel(UUID userId, UUID projectId, UUID runId) {
        access.requireOwned(projectId, userId);
        TestRun run = runs.findByIdAndProjectId(runId, projectId).orElseThrow(() -> ApiException.notFound("Run"));
        if (run.getStatus().isFinished() || !executor.cancel(runId)) {
            throw new ApiException(ErrorCode.CONFLICT, "The run has already finished.");
        }
        audit.record(AuditEventType.RUN_CANCELLED, userId, null, projectId, Map.of("runId", runId.toString()));
        return RunView.from(runs.findByIdAndProjectId(runId, projectId).orElseThrow());
    }

    /** The contract of a specification version (empty when the project has none). */
    private ContractContext contract(UUID versionId) {
        if (versionId == null) {
            return ContractContext.empty();
        }
        SpecificationVersion version = versions.findById(versionId).orElse(null);
        if (version == null) {
            return ContractContext.empty();
        }
        List<DocumentedOperation> documented = operations.findBySpecificationVersionIdOrderBySortOrderAsc(versionId).stream()
                .map(operation -> new DocumentedOperation(operation.getMethod(), operation.getPath(), operation.getResponses()))
                .toList();
        return new ContractContext(new ContractValidator(version.getComponentSchemas()), ContractValidator.index(documented));
    }

    private void reserveSlot(UUID userId) {
        if (!executor.reserve(userId)) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "You already have %d executions in progress; wait for one to "
                    .formatted(properties.maxConcurrentRunsPerUser()) + "finish or cancel it.");
        }
    }

    private List<TestCase> select(UUID projectId, RunRequest request) {
        if (request.testIds() != null && !request.testIds().isEmpty()) {
            Set<UUID> ids = new LinkedHashSet<>(request.testIds());
            List<TestCase> found = testCases.findByProjectIdAndIdIn(projectId, ids);
            if (found.size() != ids.size()) {
                throw ApiException.notFound("Test");
            }
            List<String> blocked = new ArrayList<>();
            for (TestCase test : found) {
                if (!test.isExecutable()) {
                    blocked.add(test.getName());
                }
            }
            if (!blocked.isEmpty()) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR, "%d selected test%s cannot run (disabled, not approved "
                        .formatted(blocked.size(), blocked.size() == 1 ? "" : "s") + "or stale): "
                        + String.join(", ", blocked.stream().limit(3).toList()) + (blocked.size() > 3 ? ", …" : "") + ".");
            }
            return found.stream()
                    .sorted(Comparator.comparing(TestCase::getOperationKey).thenComparing(TestCase::getSortOrder))
                    .toList();
        }
        return testCases.findExecutable(projectId).stream()
                .filter(test -> request.operationKey() == null || test.getOperationKey().equals(request.operationKey()))
                .filter(test -> request.suiteId() == null || test.getSuiteId().equals(request.suiteId()))
                .toList();
    }

    private static RunExecutor.Item item(TestCase test) {
        int space = test.getOperationKey().indexOf(' ');
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", test.getName());
        snapshot.put("category", test.getCategory().name());
        snapshot.put("origin", test.getOrigin().name());
        if (test.getScenarioKey() != null) {
            snapshot.put("scenarioKey", test.getScenarioKey());
        }
        Expectations expectations = test.getExpectationsValue();
        snapshot.put("expectations", expectations.toMap());
        return new RunExecutor.Item(test.getId(), test.getOperationKey(), test.getOperationKey().substring(0, space),
                test.getOperationKey().substring(space + 1), test.getRiskLevel(), snapshot, test.getRequest(), expectations);
    }
}
