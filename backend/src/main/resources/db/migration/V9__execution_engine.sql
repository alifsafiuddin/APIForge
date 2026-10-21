-- Secure execution engine (Phase 5): reusable request configuration, ad hoc executions,
-- cancellation and correlation ids.

-- Headers added to every request of an environment ({{variables}} allowed in values).
ALTER TABLE environments
    ADD COLUMN default_headers jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE test_runs
    -- TEST_RUN executes stored test cases; AD_HOC is a single request sent from the console.
    ADD COLUMN kind          varchar(20)  NOT NULL DEFAULT 'TEST_RUN',
    -- Why a run ended the way it did (cancelled by the user, interrupted by a restart, ...).
    ADD COLUMN status_reason varchar(500),
    ADD CONSTRAINT ck_test_runs_kind CHECK (kind IN ('TEST_RUN', 'AD_HOC'));

ALTER TABLE test_results
    -- Sent to the target as X-Request-Id, so a result can be matched with the target's own logs.
    ADD COLUMN correlation_id varchar(64);

-- CANCELLED: the request was aborted because the run was cancelled.
ALTER TABLE test_results DROP CONSTRAINT ck_test_results_error_kind;
ALTER TABLE test_results ADD CONSTRAINT ck_test_results_error_kind CHECK (error_kind IS NULL OR error_kind IN (
    'TIMEOUT', 'CONNECT_FAILED', 'DNS_FAILURE', 'TLS_ERROR', 'BLOCKED_BY_POLICY', 'RESPONSE_TOO_LARGE',
    'INVALID_RESPONSE', 'REDIRECT_BLOCKED', 'VARIABLE_UNRESOLVED', 'CANCELLED', 'INTERNAL'));

-- Results of a run in execution order.
CREATE INDEX ix_test_runs_project_kind ON test_runs (project_id, kind, created_at DESC);

ALTER TABLE audit_events DROP CONSTRAINT ck_audit_events_type;
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_type CHECK (event_type IN (
    'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT',
    'PROJECT_CREATED', 'PROJECT_DELETED', 'CROSS_USER_ACCESS_DENIED',
    'SPECIFICATION_IMPORTED', 'SPECIFICATION_ACTIVATED', 'TESTS_GENERATED',
    'ENVIRONMENT_CHANGED', 'SECRET_CHANGED', 'RUN_STARTED', 'RUN_CANCELLED', 'OUTBOUND_BLOCKED'));
