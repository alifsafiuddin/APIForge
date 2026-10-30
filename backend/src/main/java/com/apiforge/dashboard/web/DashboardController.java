package com.apiforge.dashboard.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.dashboard.service.DashboardService;
import com.apiforge.dashboard.service.DashboardViews.Dashboard;
import com.apiforge.dashboard.service.DashboardViews.ProjectOverview;

/** Summaries for the home page and each project's overview tab. */
@RestController
public class DashboardController {

    private final DashboardService dashboard;

    public DashboardController(DashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/api/v1/dashboard")
    public Dashboard dashboard(@AuthenticationPrincipal AuthenticatedUser user) {
        return dashboard.dashboard(user.id());
    }

    @GetMapping("/api/v1/projects/{projectId}/overview")
    public ProjectOverview overview(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId) {
        return dashboard.overview(user.id(), projectId);
    }
}
