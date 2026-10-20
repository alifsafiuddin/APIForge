package com.apiforge.testcase.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.apiforge.common.persistence.AuditableEntity;

/** A named group of test cases within a project. */
@Entity
@Table(name = "test_suites")
public class TestSuite extends AuditableEntity {

    public enum Kind {
        /** Holds deterministic, schema-derived tests. */
        GENERATED,
        /** Holds AI suggestions awaiting or after human review. */
        AI_SUGGESTIONS,
        /** Created and curated by the user. */
        CUSTOM
    }

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20)
    private Kind kind;

    protected TestSuite() {
    }

    public TestSuite(UUID projectId, String name, String description, Kind kind) {
        this.projectId = projectId;
        this.name = name;
        this.description = description;
        this.kind = kind;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Kind getKind() {
        return kind;
    }
}
