-- Deterministic test generation (Phase 4): stable scenario identity, user curation and provenance.

ALTER TABLE test_cases
    -- Stable identity of a generated scenario within its operation, e.g. "body.missing:/name".
    -- Together with the operation key it determines dedupe_hash, so regeneration updates in place.
    ADD COLUMN scenario_key  varchar(300),
    -- Where in the specification the scenario comes from (JSON pointer or parameter location).
    ADD COLUMN spec_location varchar(1000),
    -- Disabled tests are kept but never executed.
    ADD COLUMN enabled       boolean NOT NULL DEFAULT true,
    -- Set when a user edits the request or expectations; regeneration then leaves the test alone.
    ADD COLUMN user_modified boolean NOT NULL DEFAULT false,
    -- Position within the operation (happy path first, then authentication, parameters, body).
    ADD COLUMN sort_order    integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_test_cases_scenario_key CHECK (origin <> 'DETERMINISTIC' OR scenario_key IS NOT NULL),
    ADD CONSTRAINT ck_test_cases_sort_order CHECK (sort_order >= 0);

-- REQUIRED_FIELD was never used (missing required body fields are MISSING_FIELD); the brief's
-- content-type, string-length and array-length scenarios get their own categories.
ALTER TABLE test_cases DROP CONSTRAINT ck_test_cases_category;
ALTER TABLE test_cases ADD CONSTRAINT ck_test_cases_category CHECK (category IN (
    'HAPPY_PATH', 'MISSING_FIELD', 'INVALID_TYPE', 'BOUNDARY_VALUE', 'ENUM_VALUE', 'STRING_LENGTH',
    'ARRAY_LENGTH', 'QUERY_PARAMETER', 'PATH_PARAMETER', 'HEADER', 'CONTENT_TYPE', 'RESPONSE_VALIDATION',
    'AUTH', 'CUSTOM'));

-- Ordered listing of one operation's tests.
CREATE INDEX ix_test_cases_project_operation_order ON test_cases (project_id, operation_key, sort_order);

-- Each project has exactly one suite that receives generated tests.
CREATE UNIQUE INDEX uq_test_suites_project_generated ON test_suites (project_id) WHERE kind = 'GENERATED';

ALTER TABLE audit_events DROP CONSTRAINT ck_audit_events_type;
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_type CHECK (event_type IN (
    'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT',
    'PROJECT_CREATED', 'PROJECT_DELETED', 'CROSS_USER_ACCESS_DENIED',
    'SPECIFICATION_IMPORTED', 'SPECIFICATION_ACTIVATED', 'TESTS_GENERATED'));
