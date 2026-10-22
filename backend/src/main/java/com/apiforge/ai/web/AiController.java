package com.apiforge.ai.web;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.ai.service.AiStatusService;
import com.apiforge.ai.service.AiViews.AiStatus;
import com.apiforge.ai.service.AiViews.Explanation;
import com.apiforge.ai.service.AiViews.SuggestionReport;
import com.apiforge.ai.service.FailureExplanationService;
import com.apiforge.ai.service.TestSuggestionService;
import com.apiforge.auth.service.AuthenticatedUser;

/** Local AI: availability, test suggestions and failure explanations (all optional features). */
@RestController
public class AiController {

    public record SuggestionRequest(@NotBlank @Size(max = 600) String operationKey) {
    }

    private final AiStatusService status;
    private final TestSuggestionService suggestions;
    private final FailureExplanationService explanations;

    public AiController(AiStatusService status, TestSuggestionService suggestions, FailureExplanationService explanations) {
        this.status = status;
        this.suggestions = suggestions;
        this.explanations = explanations;
    }

    /** @param refresh check again now instead of using the briefly cached state */
    @GetMapping("/api/v1/ai/status")
    public AiStatus status(@RequestParam(defaultValue = "false") boolean refresh) {
        if (refresh) {
            status.invalidate();
        }
        return status.status();
    }

    @PostMapping("/api/v1/projects/{projectId}/ai/suggestions")
    public SuggestionReport suggest(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @Valid @RequestBody SuggestionRequest body) {
        return suggestions.suggest(user.id(), projectId, body.operationKey());
    }

    @GetMapping("/api/v1/projects/{projectId}/runs/{runId}/results/{resultId}/explanation")
    public Explanation explanation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID resultId) {
        return explanations.latest(user.id(), projectId, runId, resultId);
    }

    @PostMapping("/api/v1/projects/{projectId}/runs/{runId}/results/{resultId}/explanation")
    public Explanation explain(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @PathVariable UUID runId, @PathVariable UUID resultId) {
        return explanations.explain(user.id(), projectId, runId, resultId);
    }
}
