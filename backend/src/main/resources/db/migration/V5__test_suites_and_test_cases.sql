-- Test suites group declarative test cases within a project.

CREATE TABLE test_suites (
    id          uuid          PRIMARY KEY,
    project_id  uuid          NOT NULL,
    name        varchar(100)  NOT NULL,
    description varchar(2000),
    kind        varchar(20)   NOT NULL,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    version     bigint        NOT NULL DEFAULT 0,
    CONSTRAINT fk_test_suites_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    CONSTRAINT uq_test_suites_id_project UNIQUE (id, project_id),
    CONSTRAINT ck_test_suites_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_test_suites_kind CHECK (kind IN ('GENERATED', 'AI_SUGGESTIONS', 'CUSTOM'))
);

CREATE UNIQUE INDEX uq_test_suites_project_name ON test_suites (project_id, lower(name));

CREATE TABLE test_cases (
    id                              uuid         PRIMARY KEY,
    project_id                      uuid         NOT NULL,
    suite_id                        uuid         NOT NULL,
    source_specification_version_id uuid,
    operation_key                   varchar(600) NOT NULL,
    name                            varchar(200) NOT NULL,
    category                        varchar(30)  NOT NULL,
    origin                          varchar(20)  NOT NULL,
    review_status                   varchar(20)  NOT NULL,
    lifecycle_status                varchar(20)  NOT NULL DEFAULT 'ACTIVE',
    risk_level                      varchar(20)  NOT NULL,
    request_template                jsonb        NOT NULL,
    expectations                    jsonb        NOT NULL,
    rationale                       text,
    generator_version               varchar(20),
    dedupe_hash                     char(64)     NOT NULL,
    reviewed_by                     uuid,
    reviewed_at                     timestamptz,
    created_at                      timestamptz  NOT NULL,
    updated_at                      timestamptz  NOT NULL,
    version                         bigint       NOT NULL DEFAULT 0,
    CONSTRAINT fk_test_cases_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    -- The suite and the source specification must belong to the same project as the test case.
    CONSTRAINT fk_test_cases_suite FOREIGN KEY (suite_id, project_id)
        REFERENCES test_suites (id, project_id) ON DELETE CASCADE,
    CONSTRAINT fk_test_cases_source_specification FOREIGN KEY (source_specification_version_id, project_id)
        REFERENCES specification_versions (id, project_id) ON DELETE SET NULL (source_specification_version_id),
    CONSTRAINT fk_test_cases_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_test_cases_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_test_cases_category CHECK (category IN (
        'HAPPY_PATH', 'REQUIRED_FIELD', 'MISSING_FIELD', 'INVALID_TYPE', 'BOUNDARY_VALUE', 'ENUM_VALUE',
        'QUERY_PARAMETER', 'PATH_PARAMETER', 'HEADER', 'RESPONSE_VALIDATION', 'AUTH', 'CUSTOM')),
    CONSTRAINT ck_test_cases_origin CHECK (origin IN ('DETERMINISTIC', 'AI_SUGGESTED', 'MANUAL')),
    CONSTRAINT ck_test_cases_review_status CHECK (review_status IN ('APPROVED', 'PENDING_REVIEW', 'REJECTED')),
    CONSTRAINT ck_test_cases_lifecycle CHECK (lifecycle_status IN ('ACTIVE', 'STALE', 'ARCHIVED')),
    CONSTRAINT ck_test_cases_risk CHECK (risk_level IN ('READ_ONLY', 'MUTATING', 'DESTRUCTIVE')),
    CONSTRAINT ck_test_cases_dedupe_hash CHECK (dedupe_hash ~ '^[0-9a-f]{64}$'),
    -- AI-suggested tests can only be approved by an explicit human review.
    CONSTRAINT ck_test_cases_ai_review CHECK (
        origin <> 'AI_SUGGESTED' OR review_status <> 'APPROVED' OR reviewed_at IS NOT NULL)
);

-- Regenerating tests is idempotent: one live test per (project, normalized definition).
CREATE UNIQUE INDEX uq_test_cases_dedupe ON test_cases (project_id, dedupe_hash) WHERE lifecycle_status <> 'ARCHIVED';
CREATE INDEX ix_test_cases_project_operation ON test_cases (project_id, operation_key);
CREATE INDEX ix_test_cases_suite ON test_cases (suite_id);
CREATE INDEX ix_test_cases_project_review ON test_cases (project_id, review_status);
