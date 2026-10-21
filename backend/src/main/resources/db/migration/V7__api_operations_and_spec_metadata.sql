-- Normalized metadata extracted at import time (Phase 3: specification import and explorer).

-- Additional, still immutable, metadata per imported version. Adding columns is DDL and does not
-- fire the row-level immutability trigger.
ALTER TABLE specification_versions
    ADD COLUMN source_name       varchar(255),
    ADD COLUMN description       text,
    ADD COLUMN tags              jsonb   NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN component_schemas jsonb   NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN operation_count   integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_specification_versions_operation_count CHECK (operation_count >= 0);

-- One row per (HTTP method, path) of an imported version, with references to parameters,
-- request bodies and responses resolved; schema references are kept as "$ref" and resolved
-- against specification_versions.component_schemas.
CREATE TABLE api_operations (
    id                       uuid          PRIMARY KEY,
    specification_version_id uuid          NOT NULL,
    sort_order               integer       NOT NULL,
    operation_key            varchar(600)  NOT NULL,
    method                   varchar(10)   NOT NULL,
    path                     varchar(500)  NOT NULL,
    operation_id             varchar(255),
    summary                  varchar(1000),
    description              text,
    tags                     jsonb         NOT NULL DEFAULT '[]'::jsonb,
    deprecated               boolean       NOT NULL DEFAULT false,
    parameters               jsonb         NOT NULL DEFAULT '[]'::jsonb,
    request_body             jsonb,
    responses                jsonb         NOT NULL DEFAULT '[]'::jsonb,
    security                 jsonb         NOT NULL DEFAULT '[]'::jsonb,
    warnings                 jsonb         NOT NULL DEFAULT '[]'::jsonb,
    CONSTRAINT fk_api_operations_specification FOREIGN KEY (specification_version_id)
        REFERENCES specification_versions (id) ON DELETE CASCADE,
    CONSTRAINT uq_api_operations_key UNIQUE (specification_version_id, operation_key),
    CONSTRAINT ck_api_operations_method CHECK (method IN ('GET', 'PUT', 'POST', 'DELETE', 'OPTIONS', 'HEAD', 'PATCH', 'TRACE')),
    CONSTRAINT ck_api_operations_key CHECK (operation_key = method || ' ' || path),
    CONSTRAINT ck_api_operations_path CHECK (path LIKE '/%'),
    CONSTRAINT ck_api_operations_sort_order CHECK (sort_order >= 0)
);

CREATE INDEX ix_api_operations_version_order ON api_operations (specification_version_id, sort_order);

-- Operations belong to an immutable specification version.
CREATE FUNCTION prevent_api_operation_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'api_operations rows are immutable' USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_api_operations_immutable
    BEFORE UPDATE ON api_operations
    FOR EACH ROW EXECUTE FUNCTION prevent_api_operation_update();

-- New audit event types.
ALTER TABLE audit_events DROP CONSTRAINT ck_audit_events_type;
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_type CHECK (event_type IN (
    'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT',
    'PROJECT_CREATED', 'PROJECT_DELETED', 'CROSS_USER_ACCESS_DENIED',
    'SPECIFICATION_IMPORTED', 'SPECIFICATION_ACTIVATED'));
