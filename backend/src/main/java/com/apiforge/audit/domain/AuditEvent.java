package com.apiforge.audit.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** An append-only security audit record (updates are also rejected by a database trigger). */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 50)
    private AuditEventType eventType;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "subject", length = 254)
    private String subject;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "details", nullable = false)
    private Map<String, Object> details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditEvent() {
    }

    public AuditEvent(AuditEventType eventType, UUID userId, String subject, UUID projectId, String ipAddress,
            String requestId, Map<String, Object> details, Instant occurredAt) {
        this.eventType = eventType;
        this.userId = userId;
        this.subject = subject;
        this.projectId = projectId;
        this.ipAddress = ipAddress;
        this.requestId = requestId;
        this.details = Map.copyOf(details);
        this.occurredAt = occurredAt;
    }

    public UUID getId() {
        return id;
    }

    public AuditEventType getEventType() {
        return eventType;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getSubject() {
        return subject;
    }

    public UUID getProjectId() {
        return projectId;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public String getRequestId() {
        return requestId;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }
}
