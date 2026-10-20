-- Users and the security audit trail.

CREATE TABLE users (
    id                 uuid         PRIMARY KEY,
    email              citext       NOT NULL,
    password_hash      varchar(255) NOT NULL,
    display_name       varchar(100) NOT NULL,
    enabled            boolean      NOT NULL DEFAULT true,
    failed_login_count integer      NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    last_login_at      timestamptz,
    created_at         timestamptz  NOT NULL,
    updated_at         timestamptz  NOT NULL,
    version            bigint       NOT NULL DEFAULT 0,
    CONSTRAINT uq_users_email UNIQUE (email),
    CONSTRAINT ck_users_email_length CHECK (char_length(email) BETWEEN 3 AND 254),
    CONSTRAINT ck_users_display_name_not_blank CHECK (btrim(display_name) <> ''),
    CONSTRAINT ck_users_failed_login_count CHECK (failed_login_count >= 0),
    -- Only encoder output ("{bcrypt}$2a$...") can be stored, never a raw password.
    CONSTRAINT ck_users_password_hash_encoded CHECK (password_hash ~ '^\{[a-z0-9]+\}.{20,}$')
);

CREATE TABLE audit_events (
    id          uuid        PRIMARY KEY,
    event_type  varchar(50) NOT NULL,
    user_id     uuid,
    -- Free-form subject, e.g. the e-mail address used in a failed sign-in attempt.
    subject     varchar(254),
    -- Deliberately no foreign key: the trail must outlive deleted projects.
    project_id  uuid,
    ip_address  varchar(45),
    request_id  varchar(64),
    details     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL,
    CONSTRAINT fk_audit_events_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE SET NULL,
    CONSTRAINT ck_audit_events_type CHECK (event_type IN (
        'USER_REGISTERED', 'LOGIN_SUCCEEDED', 'LOGIN_FAILED', 'ACCOUNT_LOCKED', 'LOGOUT',
        'PROJECT_CREATED', 'PROJECT_DELETED', 'CROSS_USER_ACCESS_DENIED'))
);

CREATE INDEX ix_audit_events_user_time ON audit_events (user_id, occurred_at DESC);
CREATE INDEX ix_audit_events_type_time ON audit_events (event_type, occurred_at DESC);

-- Append-only: rows may only change through the ON DELETE SET NULL of user_id.
CREATE FUNCTION prevent_audit_event_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (to_jsonb(NEW) - 'user_id') IS DISTINCT FROM (to_jsonb(OLD) - 'user_id') THEN
        RAISE EXCEPTION 'audit_events rows are append-only' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_events_append_only
    BEFORE UPDATE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_update();
