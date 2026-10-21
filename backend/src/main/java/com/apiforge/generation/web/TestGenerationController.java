package com.apiforge.generation.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.generation.service.TestGenerationService;
import com.apiforge.generation.service.TestGenerationService.GenerationPlan;
import com.apiforge.generation.service.TestGenerationService.GenerationReport;

@RestController
@Validated
@RequestMapping("/api/v1/projects/{projectId}/tests")
public class TestGenerationController {

    private final TestGenerationService generation;

    public TestGenerationController(TestGenerationService generation) {
        this.generation = generation;
    }

    /** @param operationKeys limits generation to these operations; omitted or empty means all */
    public record GenerateRequest(@Size(max = 2000) List<@NotBlank @Size(max = 600) String> operationKeys) {
    }

    @PostMapping("/generate")
    public GenerationReport generate(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @Valid @RequestBody(required = false) GenerateRequest body) {
        return generation.generate(user.id(), projectId, body == null ? null : body.operationKeys());
    }

    /** The scenarios generation produces for one operation, and those it skips with the reason. */
    @GetMapping("/generation-plan")
    public GenerationPlan plan(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @RequestParam @NotBlank @Size(max = 600) String operationKey) {
        return generation.plan(user.id(), projectId, operationKey);
    }
}
