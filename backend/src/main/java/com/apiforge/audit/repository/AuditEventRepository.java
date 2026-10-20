package com.apiforge.audit.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.audit.domain.AuditEvent;
import com.apiforge.audit.domain.AuditEventType;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {

    List<AuditEvent> findByUserIdAndEventTypeOrderByOccurredAtDesc(UUID userId, AuditEventType eventType);

    List<AuditEvent> findBySubjectAndEventTypeOrderByOccurredAtDesc(String subject, AuditEventType eventType);
}
