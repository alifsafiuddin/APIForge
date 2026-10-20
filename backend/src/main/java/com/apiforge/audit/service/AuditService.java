package com.apiforge.audit.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEvent;
import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.repository.AuditEventRepository;
import com.apiforge.common.web.RequestMetadata;

/**
 * Records security events.
 *
 * <ul>
 * <li>{@link #record} joins the caller's transaction, so the event commits or rolls back with
 * the change it describes (and can reference rows created in that same transaction).</li>
 * <li>{@link #recordIndependently} commits on its own, for events that must survive the
 * failure of the surrounding operation (for example access that is about to be rejected).</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger("com.apiforge.audit");

    private final AuditEventRepository events;
    private final Clock clock;

    public AuditService(AuditEventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public void record(AuditEventType type, UUID userId, String subject, UUID projectId, Map<String, Object> details) {
        save(type, userId, subject, projectId, details);
    }

    @Transactional
    public void record(AuditEventType type, UUID userId, UUID projectId) {
        save(type, userId, null, projectId, Map.of());
    }

    /** Must only reference rows that are already committed (the new transaction cannot see others). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordIndependently(AuditEventType type, UUID userId, String subject, UUID projectId,
            Map<String, Object> details) {
        save(type, userId, subject, projectId, details);
    }

    private void save(AuditEventType type, UUID userId, String subject, UUID projectId, Map<String, Object> details) {
        events.save(new AuditEvent(type, userId, truncate(subject), projectId, RequestMetadata.clientIp(),
                RequestMetadata.requestId(), details, Instant.now(clock)));
        // The log line identifies users by id only; e-mail addresses stay in the database.
        log.info("audit event={} userId={} projectId={}", type, userId, projectId);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 254 ? value : value.substring(0, 254);
    }
}
