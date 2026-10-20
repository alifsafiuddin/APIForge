package com.apiforge.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.auth.domain.User;
import com.apiforge.auth.repository.UserRepository;
import com.apiforge.config.ApiForgeProperties;

/** Failed-attempt counting and temporary account lockout. */
@Service
public class LoginAttemptService {

    private final UserRepository users;
    private final AuditService audit;
    private final ApiForgeProperties.Auth settings;
    private final Clock clock;

    public LoginAttemptService(UserRepository users, AuditService audit, ApiForgeProperties properties, Clock clock) {
        this.users = users;
        this.audit = audit;
        this.settings = properties.auth();
        this.clock = clock;
    }

    /** Committed independently so the failure counts even though the sign-in request fails. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String normalizedEmail) {
        User user = users.findByEmailForUpdate(normalizedEmail).orElse(null);
        UUID userId = user == null ? null : user.getId();
        audit.record(AuditEventType.LOGIN_FAILED, userId, normalizedEmail, null, Map.of("reason", "BAD_CREDENTIALS"));
        if (user != null
                && user.recordFailedLogin(Instant.now(clock), settings.maxFailedAttempts(), settings.lockoutDuration())) {
            audit.record(AuditEventType.ACCOUNT_LOCKED, userId, normalizedEmail, null,
                    Map.of("lockedUntil", user.getLockedUntil().toString()));
        }
    }

    /**
     * Called after the password was verified. Returns {@code false} (and records the attempt)
     * if the account is currently locked; otherwise resets the counter and records the sign-in.
     */
    @Transactional
    public boolean completeLogin(UUID userId) {
        User user = users.findByIdForUpdate(userId).orElse(null);
        if (user == null) {
            return false;
        }
        Instant now = Instant.now(clock);
        if (user.isLockedAt(now)) {
            audit.record(AuditEventType.LOGIN_FAILED, userId, user.getEmail(), null, Map.of("reason", "ACCOUNT_LOCKED"));
            return false;
        }
        user.recordSuccessfulLogin(now);
        audit.record(AuditEventType.LOGIN_SUCCEEDED, userId, null);
        return true;
    }
}
