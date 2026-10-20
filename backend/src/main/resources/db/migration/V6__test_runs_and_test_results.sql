-- Execution history: a run executes tests in an environment; each test produces one result.
-- Runs keep snapshots of the environment so later edits never rewrite history.

CREATE TABLE test_runs (
    id                        uuid          PRIMARY KEY,
    project_id                uuid          NOT NULL,
    environment_id            uuid,
    suite_id                  uuid,
    specification_version_id  uuid,
    triggered_by              uuid,
    status                    varchar(20)   NOT NULL,
    environment_name_snapshot varchar(100),
    base_url_snapshot         varchar(2048),
    total_count               integer       NOT NULL DEFAULT 0,
    passed_count              integer       NOT NULL DEFAULT 0,
    failed_count              integer       NOT NULL DEFAULT 0,
    inconclusive_count        integer       NOT NULL DEFAULT 0,
    error_count               integer       NOT NULL DEFAULT 0,
    skipped_count             integer       NOT NULL DEFAULT 0,
    created_at                timestamptz   NOT NULL,
    started_at                timestamptz,
    finished_at               timestamptz,
    CONSTRAINT fk_test_runs_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_test_runs_environment FOREIGN KEY (environment_id, project_id)
        REFERENCES environments (id, project_id) ON DELETE SET NULL (environment_id),
    CONSTRAINT fk_test_runs_suite FOREIGN KEY (suite_id, project_id)
        REFERENCES test_suites (id, project_id) ON DELETE SET NULL (suite_id),
    CONSTRAINT fk_test_runs_specification FOREIGN KEY (specification_version_id, project_id)
        REFERENCES specification_versions (id, project_id) ON DELETE SET NULL (specification_version_id),
    CONSTRAINT fk_test_runs_triggered_by FOREIGN KEY (triggered_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_test_runs_status CHECK (status IN ('QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'INTERRUPTED')),
    CONSTRAINT ck_test_runs_counts CHECK (
        total_count >= 0 AND passed_count >= 0 AND failed_count >= 0 AND inconclusive_count >= 0
        AND error_count >= 0 AND skipped_count >= 0
        AND passed_count + failed_count + inconclusive_count + error_count + skipped_count <= total_count),
    CONSTRAINT ck_test_runs_timeline CHECK (
        (started_at IS NULL OR started_at >= created_at)
        AND (finished_at IS NULL OR (started_at IS NOT NULL AND finished_at >= started_at)))
);

CREATE INDEX ix_test_runs_project_created ON test_runs (project_id, created_at DESC);
CREATE INDEX ix_test_runs_project_status ON test_runs (project_id, status);

CREATE TABLE test_results (
    id                    uuid          PRIMARY KEY,
    test_run_id           uuid          NOT NULL,
    test_case_id          uuid,
    operation_key         varchar(600)  NOT NULL,
    test_case_snapshot    jsonb         NOT NULL,
    request_snapshot      jsonb         NOT NULL,
    response_status       integer,
    response_headers      jsonb,
    response_body         text,
    response_size_bytes   bigint,
    response_truncated    boolean       NOT NULL DEFAULT false,
    response_content_type varchar(255),
    duration_ms           integer,
    outcome               varchar(20)   NOT NULL,
    checks                jsonb         NOT NULL DEFAULT '[]'::jsonb,
    error_kind            varchar(30),
    error_message         varchar(1000),
    executed_at           timestamptz   NOT NULL,
    CONSTRAINT fk_test_results_run FOREIGN KEY (test_run_id) REFERENCES test_runs (id) ON DELETE CASCADE,
    CONSTRAINT fk_test_results_test_case FOREIGN KEY (test_case_id) REFERENCES test_cases (id) ON DELETE SET NULL,
    CONSTRAINT ck_test_results_status CHECK (response_status IS NULL OR response_status BETWEEN 100 AND 599),
    CONSTRAINT ck_test_results_size CHECK (response_size_bytes IS NULL OR response_size_bytes >= 0),
    CONSTRAINT ck_test_results_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT ck_test_results_outcome CHECK (outcome IN ('PASSED', 'FAILED', 'INCONCLUSIVE', 'ERROR', 'SKIPPED')),
    CONSTRAINT ck_test_results_error_kind CHECK (error_kind IS NULL OR error_kind IN (
        'TIMEOUT', 'CONNECT_FAILED', 'DNS_FAILURE', 'TLS_ERROR', 'BLOCKED_BY_POLICY', 'RESPONSE_TOO_LARGE',
        'INVALID_RESPONSE', 'REDIRECT_BLOCKED', 'VARIABLE_UNRESOLVED', 'INTERNAL')),
    -- An ERROR always says why; a PASSED result always has a real response and no transport error.
    CONSTRAINT ck_test_results_error_has_kind CHECK (outcome <> 'ERROR' OR error_kind IS NOT NULL),
    CONSTRAINT ck_test_results_passed_has_response CHECK (
        outcome <> 'PASSED' OR (response_status IS NOT NULL AND error_kind IS NULL))
);

CREATE INDEX ix_test_results_run ON test_results (test_run_id, executed_at);
CREATE INDEX ix_test_results_test_case ON test_results (test_case_id);
CREATE INDEX ix_test_results_operation_outcome ON test_results (operation_key, outcome);

-- Results are history: only the ON DELETE SET NULL of test_case_id may modify a row.
CREATE FUNCTION prevent_test_result_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (to_jsonb(NEW) - 'test_case_id') IS DISTINCT FROM (to_jsonb(OLD) - 'test_case_id') THEN
        RAISE EXCEPTION 'test_results rows are immutable' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_test_results_immutable
    BEFORE UPDATE ON test_results
    FOR EACH ROW EXECUTE FUNCTION prevent_test_result_update();
