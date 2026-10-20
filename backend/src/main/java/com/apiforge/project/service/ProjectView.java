package com.apiforge.project.service;

import java.time.Instant;
import java.util.UUID;

import com.apiforge.project.domain.Project;

/** The API representation of a project. {@code version} must be echoed back on updates. */
public record ProjectView(UUID id, String name, String description, boolean archived, Instant archivedAt,
        UUID activeSpecificationVersionId, Instant createdAt, Instant updatedAt, long version) {

    static ProjectView from(Project project) {
        return new ProjectView(project.getId(), project.getName(), project.getDescription(), project.isArchived(),
                project.getArchivedAt(), project.getActiveSpecificationVersionId(), project.getCreatedAt(),
                project.getUpdatedAt(), project.getVersion());
    }
}
