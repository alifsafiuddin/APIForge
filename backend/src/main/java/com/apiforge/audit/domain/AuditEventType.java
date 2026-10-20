package com.apiforge.audit.domain;

/** Security-relevant events. Keep in sync with ck_audit_events_type (V2 migration). */
public enum AuditEventType {
    USER_REGISTERED,
    LOGIN_SUCCEEDED,
    LOGIN_FAILED,
    ACCOUNT_LOCKED,
    LOGOUT,
    PROJECT_CREATED,
    PROJECT_DELETED,
    CROSS_USER_ACCESS_DENIED
}
