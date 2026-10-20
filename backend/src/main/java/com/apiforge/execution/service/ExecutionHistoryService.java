package com.apiforge.execution.service;

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
import com.apiforge.execution.service.ExecutionHistoryViews.ResultDetail;
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

    public Page<ResultSummary> listResults(UUID userId, UUID projectId, UUID runId, Pageable pageable) {
        TestRun run = requireRun(userId, projectId, runId);
        Pageable page = Pagination.sanitize(pageable, RESULT_SORTS, Sort.by(Sort.Order.asc("executedAt")));
        return results.findByTestRunId(run.getId(), page).map(ResultSummary::from);
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
