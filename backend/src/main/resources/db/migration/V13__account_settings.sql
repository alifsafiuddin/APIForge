-- Account settings (Phase 9): profile, password change and account deletion.

-- When the password last changed. Sessions signed in before this moment are ended, so changing the
-- password signs out every other browser.
ALTER TABLE users ADD COLUMN credentials_changed_at timestamptz;

ALTER TABLE audit_events DROP CONSTRAINT ck_audit_events_type;
ALTER TABLE audit_events ADD CONSTRAINT ck_audit_events_type CHECK (event_type IN (
    'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT',
    'PROJECT_CREATED', 'PROJECT_DELETED', 'CROSS_USER_ACCESS_DENIED',
    'SPECIFICATION_IMPORTED', 'SPECIFICATION_ACTIVATED', 'TESTS_GENERATED',
    'ENVIRONMENT_CHANGED', 'SECRET_CHANGED', 'RUN_STARTED', 'RUN_CANCELLED', 'OUTBOUND_BLOCKED',
    'AI_SUGGESTIONS_REQUESTED', 'AI_EXPLANATION_REQUESTED',
    'PROFILE_UPDATED', 'PASSWORD_CHANGED', 'ACCOUNT_DELETED'));
