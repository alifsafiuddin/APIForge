package com.apiforge.execution.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.web.Pagination;
import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.repository.TestResultRepository;
import com.apiforge.execution.repository.TestRunRepository;
import com.apiforge.execution.service.ExecutionHistoryViews.Change;
import com.apiforge.execution.service.ExecutionHistoryViews.ChangeKind;
import com.apiforge.execution.service.ExecutionHistoryViews.ResultDetail;
import com.apiforge.execution.service.ExecutionHistoryViews.RunComparison;
import com.apiforge.execution.service.ExecutionHistoryViews.ResultSummary;
import com.apiforge.execution.service.ExecutionHistoryViews.RunView;
import com.apiforge.project.service.ProjectAccessService;

/**
 * Read access to execution history. Every lookup is scoped to a project the caller owns, and
 * runs and results are always loaded through their parent, never by id alone.
 */
@Service
@Transactional(readOnly = true)
public class ExecutionHistoryService {

    static final Map<String, String> RUN_SORTS = Map.of("createdAt", "createdAt", "startedAt", "startedAt",
            "finishedAt", "finishedAt", "status", "status");
    static final Map<String, String> RESULT_SORTS = Map.of("executedAt", "executedAt", "outcome", "outcome",
            "durationMs", "durationMs");

    private final ProjectAccessService access;
    private final TestRunRepository runs;
    private final TestResultRepository results;

    public ExecutionHistoryService(ProjectAccessService access, TestRunRepository runs, TestResultRepository results) {
        this.access = access;
        this.runs = runs;
        this.results = results;
    }

    public Page<RunView> listRuns(UUID userId, UUID projectId, Pageable pageable) {
        access.requireOwned(projectId, userId);
        Pageable page = Pagination.sanitize(pageable, RUN_SORTS, Sort.by(Sort.Order.desc("createdAt")));
        return runs.findByProjectId(projectId, page).map(RunView::from);
    }

    public RunView getRun(UUID userId, UUID projectId, UUID runId) {
        return RunView.from(requireRun(userId, projectId, runId));
    }

    /** @param outcome only results with this outcome, or all when {@code null} */
    public Page<ResultSummary> listResults(UUID userId, UUID projectId, UUID runId, TestResult.Outcome outcome,
            Pageable pageable) {
        TestRun run = requireRun(userId, projectId, runId);
        Pageable page = Pagination.sanitize(pageable, RESULT_SORTS, Sort.by(Sort.Order.asc("executedAt")));
        return (outcome == null ? results.findByTestRunId(run.getId(), page)
                : results.findByTestRunIdAndOutcome(run.getId(), outcome, page)).map(ResultSummary::from);
    }

    /**
     * Outcome changes between {@code runId} and a baseline run of the same project (by default the
     * previous run of the same kind). Tests are matched by test case id, otherwise by operation and
     * name.
     */
    public RunComparison compare(UUID userId, UUID projectId, UUID runId, UUID baselineId) {
        TestRun current = requireRun(userId, projectId, runId);
        TestRun baseline = baselineId != null ? requireRun(userId, projectId, baselineId)
                : runs.findFirstByProjectIdAndKindAndCreatedAtLessThanOrderByCreatedAtDesc(projectId, current.getKind(),
                        current.getCreatedAt()).orElseThrow(() -> ApiException.notFound("Earlier run to compare with"));
        Map<String, TestResult> before = new LinkedHashMap<>();
        results.findByTestRunIdOrderByExecutedAtAsc(baseline.getId()).forEach(result -> before.put(key(result), result));
        List<Change> changes = new ArrayList<>();
        for (TestResult after : results.findByTestRunIdOrderByExecutedAtAsc(current.getId())) {
            TestResult previous = before.remove(key(after));
            TestResult.Outcome old = previous == null ? null : previous.getOutcome();
            changes.add(new Change(ExecutionHistoryViews.name(after), after.getOperationKey(), after.getTestCaseId(), old,
                    after.getOutcome(), kind(old, after.getOutcome()), previous == null ? null : previous.getId(),
                    after.getId()));
        }
        for (TestResult removed : before.values()) {
            changes.add(new Change(ExecutionHistoryViews.name(removed), removed.getOperationKey(), removed.getTestCaseId(),
                    removed.getOutcome(), null, ChangeKind.REMOVED, removed.getId(), null));
        }
        Map<ChangeKind, Integer> counts = new EnumMap<>(ChangeKind.class);
        for (ChangeKind kind : ChangeKind.values()) {
            counts.put(kind, 0);
        }
        changes.forEach(change -> counts.merge(change.kind(), 1, Integer::sum));
        changes.sort(Comparator.comparing((Change change) -> change.kind().ordinal())
                .thenComparing(Change::operationKey).thenComparing(change -> String.valueOf(change.testName())));
        return new RunComparison(RunView.from(baseline), RunView.from(current), counts, changes);
    }

    private static String key(TestResult result) {
        return result.getTestCaseId() != null ? result.getTestCaseId().toString()
                : result.getOperationKey() + "|" + ExecutionHistoryViews.name(result);
    }

    private static ChangeKind kind(TestResult.Outcome before, TestResult.Outcome after) {
        if (before == null) {
            return ChangeKind.NEW;
        }
        if (before == after) {
            return ChangeKind.UNCHANGED;
        }
        if (after == TestResult.Outcome.PASSED) {
            return ChangeKind.FIXED;
        }
        return before == TestResult.Outcome.PASSED ? ChangeKind.REGRESSED : ChangeKind.CHANGED;
    }

    public ResultDetail getResult(UUID userId, UUID projectId, UUID runId, UUID resultId) {
        TestRun run = requireRun(userId, projectId, runId);
        TestResult result = results.findById(resultId)
                .filter(candidate -> candidate.getTestRunId().equals(run.getId()))
                .orElseThrow(() -> ApiException.notFound("Test result"));
        return ResultDetail.from(result);
    }

    private TestRun requireRun(UUID userId, UUID projectId, UUID runId) {
        access.requireOwned(projectId, userId);
        return runs.findByIdAndProjectId(runId, projectId).orElseThrow(() -> ApiException.notFound("Test run"));
    }
}
