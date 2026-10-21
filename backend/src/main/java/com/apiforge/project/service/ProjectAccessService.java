package com.apiforge.project.service;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.project.domain.Project;
import com.apiforge.project.repository.ProjectRepository;

/**
 * The single authorization point for project-scoped data. Every service that reads or changes
 * a project or anything inside it (environments, tests, runs, results) obtains the project
 * through {@link #requireOwned}.
 *
 * <p>A project that exists but belongs to someone else is reported exactly like a missing one
 * (404), so project ids cannot be probed. Such attempts are audited.
 */
@Service
public class ProjectAccessService {

    private static final Logger log = LoggerFactory.getLogger(ProjectAccessService.class);

    private final ProjectRepository projects;
    private final AuditService audit;

    public ProjectAccessService(ProjectRepository projects, AuditService audit) {
        this.projects = projects;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Project requireOwned(UUID projectId, UUID userId) {
        return projects.findByIdAndOwnerId(projectId, userId).orElseThrow(() -> denied(projectId, userId));
    }

    /** As {@link #requireOwned}, additionally locking the project row until the transaction ends. */
    @Transactional
    public Project requireOwnedForUpdate(UUID projectId, UUID userId) {
        return projects.findByIdAndOwnerIdForUpdate(projectId, userId).orElseThrow(() -> denied(projectId, userId));
    }

    private ApiException denied(UUID projectId, UUID userId) {
        if (projects.existsById(projectId)) {
            log.warn("User {} attempted to access project {} owned by another user", userId, projectId);
            audit.recordIndependently(AuditEventType.CROSS_USER_ACCESS_DENIED, userId, null, projectId, Map.of());
        }
        return ApiException.notFound("Project");
    }
}
