package com.apiforge.project.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.apiforge.common.persistence.AuditableEntity;

/** An API project, owned by exactly one user. Child resources are always reached through it. */
@Entity
@Table(name = "api_projects")
public class Project extends AuditableEntity {

    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Column(name = "active_specification_version_id")
    private UUID activeSpecificationVersionId;

    @Column(name = "archived_at")
    private Instant archivedAt;

    protected Project() {
    }

    public Project(UUID ownerId, String name, String description) {
        this.ownerId = ownerId;
        this.name = name;
        this.description = description;
    }

    public void rename(String name) {
        this.name = name;
    }

    public void describe(String description) {
        this.description = description;
    }

    public void archive(Instant now) {
        if (archivedAt == null) {
            archivedAt = now;
        }
    }

    public void unarchive() {
        archivedAt = null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getActiveSpecificationVersionId() {
        return activeSpecificationVersionId;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }
}
