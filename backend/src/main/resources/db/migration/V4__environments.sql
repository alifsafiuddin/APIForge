-- Execution environments (target base URL, auth settings, execution policy) and their variables.

CREATE TABLE environments (
    id               uuid          PRIMARY KEY,
    project_id       uuid          NOT NULL,
    name             varchar(100)  NOT NULL,
    base_url         varchar(2048) NOT NULL,
    timeout_ms       integer       NOT NULL DEFAULT 15000,
    follow_redirects boolean       NOT NULL DEFAULT false,
    execution_policy varchar(20)   NOT NULL DEFAULT 'READ_ONLY',
    auth_type        varchar(20)   NOT NULL DEFAULT 'NONE',
    -- Non-secret settings only (header names, variable names); credentials live in environment_variables.
    auth_config      jsonb         NOT NULL DEFAULT '{}'::jsonb,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    version          bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_environments_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    CONSTRAINT uq_environments_id_project UNIQUE (id, project_id),
    CONSTRAINT ck_environments_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_environments_timeout CHECK (timeout_ms BETWEEN 100 AND 60000),
    CONSTRAINT ck_environments_policy CHECK (execution_policy IN ('READ_ONLY', 'ALLOW_MUTATING', 'ALLOW_DESTRUCTIVE')),
    CONSTRAINT ck_environments_auth_type CHECK (auth_type IN ('NONE', 'BEARER', 'BASIC', 'API_KEY_HEADER', 'API_KEY_QUERY'))
);

CREATE UNIQUE INDEX uq_environments_project_name ON environments (project_id, lower(name));

CREATE TABLE environment_variables (
    id              uuid        PRIMARY KEY,
    environment_id  uuid        NOT NULL,
    name            varchar(64) NOT NULL,
    secret          boolean     NOT NULL,
    plain_value     text,
    encrypted_value bytea,
    key_id          varchar(32),
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint      NOT NULL DEFAULT 0,
    CONSTRAINT fk_environment_variables_environment FOREIGN KEY (environment_id) REFERENCES environments (id) ON DELETE CASCADE,
    CONSTRAINT uq_environment_variables_name UNIQUE (environment_id, name),
    CONSTRAINT ck_environment_variables_name CHECK (name ~ '^[A-Za-z_][A-Za-z0-9_]{0,63}$'),
    -- A secret can only be stored encrypted; a plain variable never uses the encrypted columns.
    CONSTRAINT ck_environment_variables_storage CHECK (
        (secret AND plain_value IS NULL AND encrypted_value IS NOT NULL AND key_id IS NOT NULL)
        OR (NOT secret AND plain_value IS NOT NULL AND encrypted_value IS NULL AND key_id IS NULL))
);
