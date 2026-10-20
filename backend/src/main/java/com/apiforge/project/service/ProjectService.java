package com.apiforge.project.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.web.Pagination;
import com.apiforge.project.domain.Project;
import com.apiforge.project.repository.ProjectRepository;

@Service
public class ProjectService {

    static final Map<String, String> SORTABLE = Map.of("name", "name", "createdAt", "createdAt", "updatedAt", "updatedAt");
    static final Sort DEFAULT_SORT = Sort.by(Sort.Order.desc("updatedAt"));

    private final ProjectRepository projects;
    private final ProjectAccessService access;
    private final AuditService audit;
    private final Clock clock;

    public ProjectService(ProjectRepository projects, ProjectAccessService access, AuditService audit, Clock clock) {
        this.projects = projects;
        this.access = access;
        this.audit = audit;
        this.clock = clock;
    }

    public record CreateProject(String name, String description) {
    }

    /** Null fields are left unchanged; an empty description clears it. */
    public record UpdateProject(String name, String description, Boolean archived, long expectedVersion) {
    }

    @Transactional(readOnly = true)
    public Page<ProjectView> list(UUID userId, boolean archived, String query, Pageable pageable) {
        Pageable page = Pagination.sanitize(pageable, SORTABLE, DEFAULT_SORT);
        return projects.search(userId, archived, toLikePattern(query), page).map(ProjectView::from);
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID userId, UUID projectId) {
        return ProjectView.from(access.requireOwned(projectId, userId));
    }

    @Transactional
    public ProjectView create(UUID userId, CreateProject command) {
        String name = normalizeName(command.name());
        ensureNameAvailable(userId, name, null);
        Project project = saveFlushed(new Project(userId, name, normalizeDescription(command.description())));
        audit.record(AuditEventType.PROJECT_CREATED, userId, project.getId());
        return ProjectView.from(project);
    }

    @Transactional
    public ProjectView update(UUID userId, UUID projectId, UpdateProject command) {
        Project project = access.requireOwned(projectId, userId);
        if (project.getVersion() != command.expectedVersion()) {
            throw new ApiException(ErrorCode.STALE_VERSION,
                    "The project was modified since you loaded it. Reload it and try again.");
        }
        // Validate the target state before mutating the managed entity: a query issued after a
        // change would auto-flush it and hit the unique index before we could report it clearly.
        String targetName = command.name() != null ? normalizeName(command.name()) : project.getName();
        boolean targetArchived = command.archived() != null ? command.archived() : project.isArchived();
        if (!targetArchived) {
            ensureNameAvailable(userId, targetName, project.getId());
        }

        project.rename(targetName);
        if (command.description() != null) {
            project.describe(normalizeDescription(command.description()));
        }
        if (targetArchived) {
            project.archive(Instant.now(clock));
        } else {
            project.unarchive();
        }
        return ProjectView.from(saveFlushed(project));
    }

    /** Deletes the project and, through foreign-key cascades, everything it contains. */
    @Transactional
    public void delete(UUID userId, UUID projectId) {
        Project project = access.requireOwned(projectId, userId);
        projects.delete(project);
        audit.record(AuditEventType.PROJECT_DELETED, userId, null, projectId, Map.of("name", project.getName()));
    }

    private void ensureNameAvailable(UUID userId, String name, UUID excludeId) {
        if (projects.existsActiveName(userId, name, excludeId)) {
            throw duplicateName(name);
        }
    }

    /** Flushes inside the transaction so a concurrent duplicate surfaces here as a clear 409. */
    private Project saveFlushed(Project project) {
        try {
            return projects.saveAndFlush(project);
        } catch (DataIntegrityViolationException ex) {
            throw duplicateName(project.getName());
        }
    }

    private static ApiException duplicateName(String name) {
        return new ApiException(ErrorCode.DUPLICATE_NAME, "You already have an active project named '%s'.".formatted(name));
    }

    private static String normalizeName(String name) {
        String normalized = name == null ? "" : name.strip();
        if (normalized.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "Project name must not be blank.");
        }
        return normalized;
    }

    private static String normalizeDescription(String description) {
        if (description == null) {
            return null;
        }
        String normalized = description.strip();
        return normalized.isEmpty() ? null : normalized;
    }

    /** Case-insensitive "contains" pattern with LIKE wildcards in the user's text escaped. */
    static String toLikePattern(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String escaped = query.strip().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
