package com.apiforge.execution.web;

import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.common.web.PageResponse;
import com.apiforge.execution.service.ExecutionHistoryService;
import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.service.ExecutionHistoryViews.ResultDetail;
import com.apiforge.execution.service.ExecutionHistoryViews.RunComparison;
import com.apiforge.execution.service.ExecutionHistoryViews.ResultSummary;
import com.apiforge.execution.service.ExecutionHistoryViews.RunView;

/** Read-only execution history of a project. Runs are created by the execution engine. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/runs")
public class ExecutionHistoryController {

    private final ExecutionHistoryService history;

    public ExecutionHistoryController(ExecutionHistoryService history) {
        this.history = history;
    }

    /** @param failing true: runs with failed or errored tests; false: completed runs without any */
    @GetMapping
    public PageResponse<RunView> listRuns(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @RequestParam(required = false) TestRun.Kind kind,
            @RequestParam(required = false) TestRun.Status status, @RequestParam(required = false) Boolean failing,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(history.listRuns(user.id(), projectId, kind, status, failing, pageable));
    }

    @GetMapping("/{runId}")
    public RunView getRun(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return history.getRun(user.id(), projectId, runId);
    }

    @GetMapping("/{runId}/results")
    public PageResponse<ResultSummary> listResults(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID projectId, @PathVariable UUID runId,
            @RequestParam(required = false) TestResult.Outcome outcome, @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(history.listResults(user.id(), projectId, runId, outcome, pageable));
    }

    /** Outcome changes against {@code baseline}, or against the previous run of the same kind. */
    @GetMapping("/{runId}/comparison")
    public RunComparison compare(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID runId, @RequestParam(required = false) UUID baseline) {
        return history.compare(user.id(), projectId, runId, baseline);
    }

    @GetMapping("/{runId}/results/{resultId}")
    public ResultDetail getResult(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID resultId) {
        return history.getResult(user.id(), projectId, runId, resultId);
    }
}
