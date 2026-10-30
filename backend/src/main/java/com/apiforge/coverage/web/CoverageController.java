package com.apiforge.coverage.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.coverage.service.CoverageService;
import com.apiforge.coverage.service.CoverageViews.CoverageReport;
import com.apiforge.coverage.service.CoverageViews.OperationDetail;
import com.apiforge.coverage.service.CoverageViews.VersionChanges;

/** Specification coverage of the active version: metrics, gaps, per-operation detail and version changes. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/coverage")
public class CoverageController {

    private final CoverageService coverage;

    public CoverageController(CoverageService coverage) {
        this.coverage = coverage;
    }

    @GetMapping
    public CoverageReport report(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @RequestParam(required = false) UUID environmentId) {
        return coverage.report(user.id(), projectId, environmentId);
    }

    /** One operation, identified by its key (e.g. {@code GET /pets/{petId}}). */
    @GetMapping("/operation")
    public OperationDetail operation(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @RequestParam String key, @RequestParam(required = false) UUID environmentId) {
        return coverage.operation(user.id(), projectId, key, environmentId);
    }

    @GetMapping("/changes")
    public VersionChanges changes(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @RequestParam(required = false) UUID from) {
        return coverage.changes(user.id(), projectId, from);
    }
}
