-- Local AI assistance (Phase 8): suggested tests and failure explanations (ADR-029).

-- The model that suggested a test. Suggestions are ordinary test cases with origin AI_SUGGESTED that
-- start PENDING_REVIEW and cannot run until a person approves them (ck_test_cases_ai_review, V5).
ALTER TABLE test_cases ADD COLUMN ai_model varchar(200);
ALTER TABLE test_cases ADD CONSTRAINT ck_test_cases_ai_model CHECK (origin = 'AI_SUGGESTED' OR ai_model IS NULL);

-- Explanations of failed results. Model output only: the prompt is never stored, and the input was
-- redacted and sanitized before it was sent.
CREATE TABLE ai_explanations (
    id             uuid          PRIMARY KEY,
    project_id     uuid          NOT NULL,
    test_result_id uuid          NOT NULL,
    model          varchar(200)  NOT NULL,
    explanation    jsonb         NOT NULL,
    latency_ms     integer       NOT NULL,
    created_by     uuid,
    created_at     timestamptz   NOT NULL,
    CONSTRAINT fk_ai_explanations_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_explanations_result FOREIGN KEY (test_result_id) REFERENCES test_results (id) ON DELETE CASCADE,
    CONSTRAINT fk_ai_explanations_created_by FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_ai_explanations_latency CHECK (latency_ms >= 0),
    CONSTRAINT ck_ai_explanations_object CHECK (jsonb_typeof(explanation) = 'object')
);

CREATE INDEX ix_ai_explanations_result ON ai_explanations (test_result_id, created_at DESC);

-- AI requests are audited with model, outcome, size and timing, never with prompt content.
ALTER TABLE audit_events DROP CONSTRAINT ck_audit_events_type;
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_type CHECK (event_type IN (
    'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT',
    'PROJECT_CREATED', 'PROJECT_DELETED', 'CROSS_USER_ACCESS_DENIED',
    'SPECIFICATION_IMPORTED', 'SPECIFICATION_ACTIVATED', 'TESTS_GENERATED',
    'ENVIRONMENT_CHANGED', 'SECRET_CHANGED', 'RUN_STARTED', 'RUN_CANCELLED', 'OUTBOUND_BLOCKED',
    'AI_SUGGESTIONS_REQUESTED', 'AI_EXPLANATION_REQUESTED'));
