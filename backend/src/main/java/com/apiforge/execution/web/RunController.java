package com.apiforge.execution.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.execution.service.ExecutionHistoryViews.ResultDetail;
import com.apiforge.execution.service.ExecutionHistoryViews.RunView;
import com.apiforge.execution.service.ExecutionService;
import com.apiforge.execution.service.ExecutionService.AdHocRequest;
import com.apiforge.execution.service.ExecutionService.RunRequest;
import com.apiforge.testcase.service.TestDefinitionValidator.RequestInput;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}")
public class RunController {

    private final ExecutionService execution;

    public RunController(ExecutionService execution) {
        this.execution = execution;
    }

    public record StartRunRequest(@NotNull UUID environmentId, @Size(max = 5000) List<@NotNull UUID> testIds,
            @Size(max = 600) String operationKey, UUID suiteId) {
    }

    public record AdHocExecutionRequest(@NotNull UUID environmentId, @NotNull @Size(max = 10) String method,
            @NotNull @Size(max = 2001) String path, @NotNull RequestInput request,
            @Size(max = 10) List<@NotNull @Size(max = 3) String> expectedStatuses) {
    }

    /** Queues a run; poll {@code GET /runs/{id}} for progress. */
    @PostMapping("/runs")
    public ResponseEntity<RunView> start(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @Valid @RequestBody StartRunRequest body) {
        RunView run = execution.startRun(user.id(), projectId,
                new RunRequest(body.environmentId(), body.testIds(), body.operationKey(), body.suiteId()));
        return ResponseEntity.accepted().location(URI.create("/api/v1/projects/" + projectId + "/runs/" + run.id()))
                .body(run);
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<RunView> cancel(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID runId) {
        return ResponseEntity.accepted().body(execution.cancel(user.id(), projectId, runId));
    }

    /** Sends one request now and returns the stored, redacted result. */
    @PostMapping("/executions")
    public ResultDetail adHoc(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @Valid @RequestBody AdHocExecutionRequest body) {
        return execution.adHoc(user.id(), projectId, new AdHocRequest(body.environmentId(), body.method(), body.path(),
                body.request(), body.expectedStatuses()));
    }
}
