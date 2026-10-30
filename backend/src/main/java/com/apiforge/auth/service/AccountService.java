package com.apiforge.auth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.auth.domain.User;
import com.apiforge.auth.repository.UserRepository;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.error.Problems.FieldErrorItem;
import com.apiforge.common.web.FixedWindowRateLimiter;

/**
 * The signed-in user's own account: display name, password and deletion. Password checks are rate
 * limited per user, so a stolen session cannot be used to guess the password.
 */
@Service
public class AccountService {

    static final int PASSWORD_CHECKS_PER_MINUTE = 5;

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuditService audit;
    private final CredentialEpochs epochs;
    private final Clock clock;
    private final FixedWindowRateLimiter passwordChecks;

    public AccountService(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            AuditService audit, CredentialEpochs epochs, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.audit = audit;
        this.epochs = epochs;
        this.clock = clock;
        this.passwordChecks = new FixedWindowRateLimiter(PASSWORD_CHECKS_PER_MINUTE, Duration.ofMinutes(1), clock);
    }

    /** @return the authentication to establish for the renamed user */
    @Transactional
    public Authentication rename(UUID userId, String displayName) {
        User user = require(userId);
        user.rename(displayName.strip());
        audit.record(AuditEventType.PROFILE_UPDATED, userId, null);
        return authenticated(users.saveAndFlush(user));
    }

    /**
     * Changes the password and ends every other session of the user (their sign-in time is before
     * the change); the caller re-establishes the current one.
     */
    @Transactional
    public Authentication changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = require(userId);
        checkPassword(user, currentPassword, "currentPassword");
        List<FieldErrorItem> errors = passwordPolicy.violations(newPassword, user.getEmail()).stream()
                .map(message -> new FieldErrorItem("newPassword", message)).toList();
        if (errors.isEmpty() && passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            errors = List.of(new FieldErrorItem("newPassword", "must differ from the current password"));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", errors);
        }
        Instant now = Instant.now(clock);
        user.changePassword(passwordEncoder.encode(newPassword), now);
        users.saveAndFlush(user);
        epochs.changed(userId, now);
        audit.record(AuditEventType.PASSWORD_CHANGED, userId, null);
        return authenticated(user);
    }

    /**
     * Deletes the user and everything they own (projects and their data, by cascade). The audit trail
     * keeps the events with the user removed.
     */
    @Transactional
    public void delete(UUID userId, String password, String confirmEmail) {
        User user = require(userId);
        checkPassword(user, password, "password");
        if (confirmEmail == null || !EmailAddresses.normalize(confirmEmail).equals(user.getEmail())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.",
                    List.of(new FieldErrorItem("confirmEmail", "must be the account's e-mail address")));
        }
        // user_id is set to NULL when the user row goes; the subject keeps which account it was.
        audit.record(AuditEventType.ACCOUNT_DELETED, userId, userId.toString(), null, Map.of());
        users.delete(user);
        users.flush();
        epochs.deleted(userId);
    }

    private void checkPassword(User user, String password, String field) {
        long retryAfter = passwordChecks.tryAcquire(user.getId().toString());
        if (retryAfter > 0) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many attempts. Try again in " + retryAfter + " s.",
                    List.of(), Map.of("retryAfterSeconds", retryAfter));
        }
        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.",
                    List.of(new FieldErrorItem(field, "is not your current password")));
        }
    }

    private User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(ErrorCode.UNAUTHENTICATED,
                "The account no longer exists."));
    }

    private static Authentication authenticated(User user) {
        AuthenticatedUser principal = AuthenticatedUser.from(user);
        principal.eraseCredentials();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }
}
