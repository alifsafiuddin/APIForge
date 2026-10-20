package com.apiforge.project.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.apiforge.auth.service.AuthenticatedUser;
import com.apiforge.common.web.PageResponse;
import com.apiforge.project.service.ProjectService;
import com.apiforge.project.service.ProjectService.CreateProject;
import com.apiforge.project.service.ProjectService.UpdateProject;
import com.apiforge.project.service.ProjectView;

@RestController
@Validated
@RequestMapping("/api/v1/projects")
public class ProjectController {

    static final String NO_CONTROL_CHARACTERS = "^[^\\p{Cntrl}]*$";

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    public record CreateProjectRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                    message = "must not contain control characters") String name,
            @Size(max = 2000) String description) {
    }

    public record UpdateProjectRequest(
            @Size(min = 1, max = 100) @Pattern(regexp = NO_CONTROL_CHARACTERS,
                    message = "must not contain control characters") String name,
            @Size(max = 2000) String description,
            Boolean archived,
            @NotNull @PositiveOrZero Long version) {
    }

    @GetMapping
    public PageResponse<ProjectView> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) @Size(max = 100) String q,
            @PageableDefault(size = 20) Pageable pageable) {
        return PageResponse.of(projectService.list(user.id(), archived, q, pageable));
    }

    @PostMapping
    public ResponseEntity<ProjectView> create(@AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateProjectRequest body) {
        ProjectView project = projectService.create(user.id(), new CreateProject(body.name(), body.description()));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + project.id())).body(project);
    }

    @GetMapping("/{projectId}")
    public ProjectView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId) {
        return projectService.get(user.id(), projectId);
    }

    @PatchMapping("/{projectId}")
    public ProjectView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId,
            @Valid @RequestBody UpdateProjectRequest body) {
        return projectService.update(user.id(), projectId,
                new UpdateProject(body.name(), body.description(), body.archived(), body.version()));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID projectId) {
        projectService.delete(user.id(), projectId);
        return ResponseEntity.noContent().build();
    }
}
