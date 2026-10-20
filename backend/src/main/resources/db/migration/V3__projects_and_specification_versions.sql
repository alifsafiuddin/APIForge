-- API projects (owned by one user) and their immutable OpenAPI specification versions.

CREATE TABLE api_projects (
    id                              uuid          PRIMARY KEY,
    owner_id                        uuid          NOT NULL,
    name                            varchar(100)  NOT NULL,
    description                     varchar(2000),
    active_specification_version_id uuid,
    archived_at                     timestamptz,
    created_at                      timestamptz   NOT NULL,
    updated_at                      timestamptz   NOT NULL,
    version                         bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_api_projects_owner FOREIGN KEY (owner_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT ck_api_projects_name_not_blank CHECK (btrim(name) <> '')
);

-- Names are unique per owner among non-archived projects (case-insensitive).
CREATE UNIQUE INDEX uq_api_projects_owner_name_active ON api_projects (owner_id, lower(name)) WHERE archived_at IS NULL;
CREATE INDEX ix_api_projects_owner_updated ON api_projects (owner_id, updated_at DESC);

CREATE TABLE specification_versions (
    id                 uuid         PRIMARY KEY,
    project_id         uuid         NOT NULL,
    version_number     integer      NOT NULL,
    openapi_version    varchar(20)  NOT NULL,
    title              varchar(500) NOT NULL,
    api_version        varchar(100),
    source_format      varchar(10)  NOT NULL,
    raw_content        text         NOT NULL,
    content_sha256     char(64)     NOT NULL,
    content_size_bytes integer      NOT NULL,
    servers            jsonb        NOT NULL DEFAULT '[]'::jsonb,
    security_schemes   jsonb        NOT NULL DEFAULT '{}'::jsonb,
    import_messages    jsonb        NOT NULL DEFAULT '[]'::jsonb,
    imported_by        uuid,
    imported_at        timestamptz  NOT NULL,
    CONSTRAINT fk_specification_versions_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_specification_versions_imported_by FOREIGN KEY (imported_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT uq_specification_versions_number UNIQUE (project_id, version_number),
    -- Target for composite foreign keys that must stay within one project.
    CONSTRAINT uq_specification_versions_id_project UNIQUE (id, project_id),
    CONSTRAINT ck_specification_versions_number CHECK (version_number > 0),
    CONSTRAINT ck_specification_versions_format CHECK (source_format IN ('JSON', 'YAML')),
    CONSTRAINT ck_specification_versions_sha256 CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_specification_versions_size CHECK (content_size_bytes > 0)
);

-- The active version must belong to the same project.
ALTER TABLE api_projects
    ADD CONSTRAINT fk_api_projects_active_specification
        FOREIGN KEY (active_specification_version_id, id)
            REFERENCES specification_versions (id, project_id)
            ON DELETE SET NULL (active_specification_version_id)
            DEFERRABLE INITIALLY DEFERRED;

-- Specification versions are immutable once imported.
CREATE FUNCTION prevent_specification_version_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (to_jsonb(NEW) - 'imported_by') IS DISTINCT FROM (to_jsonb(OLD) - 'imported_by') THEN
        RAISE EXCEPTION 'specification_versions rows are immutable' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_specification_versions_immutable
    BEFORE UPDATE ON specification_versions
    FOR EACH ROW EXECUTE FUNCTION prevent_specification_version_update();
