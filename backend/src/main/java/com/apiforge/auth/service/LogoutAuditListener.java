package com.apiforge.auth.service;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.LogoutSuccessEvent;
import org.springframework.stereotype.Component;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;

/** Records sign-outs, which Spring Security's logout filter announces as an application event. */
@Component
public class LogoutAuditListener {

    private final AuditService audit;

    public LogoutAuditListener(AuditService audit) {
        this.audit = audit;
    }

    @EventListener
    public void onLogout(LogoutSuccessEvent event) {
        if (event.getAuthentication().getPrincipal() instanceof AuthenticatedUser user) {
            audit.record(AuditEventType.LOGOUT, user.id(), null);
        }
    }
}
