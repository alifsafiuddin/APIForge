package com.apiforge.auth.service;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
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
import com.apiforge.config.ApiForgeProperties;

/** Registration and credential verification. Session handling lives in the web layer. */
@Service
public class AuthService {

    /**
     * One message for every sign-in failure (unknown e-mail, wrong password, locked or disabled
     * account) so responses never reveal whether an account exists.
     */
    static final String INVALID_CREDENTIALS =
            "Invalid e-mail or password. After repeated failures, sign-in is temporarily locked.";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final AuthenticationManager authenticationManager;
    private final LoginAttemptService loginAttempts;
    private final AuditService audit;
    private final ApiForgeProperties.Auth settings;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, PasswordPolicy passwordPolicy,
            AuthenticationManager authenticationManager, LoginAttemptService loginAttempts, AuditService audit,
            ApiForgeProperties properties) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.authenticationManager = authenticationManager;
        this.loginAttempts = loginAttempts;
        this.audit = audit;
        this.settings = properties.auth();
    }

    @Transactional
    public Authentication register(String email, String password, String displayName) {
        if (!settings.registrationEnabled()) {
            throw new ApiException(ErrorCode.REGISTRATION_DISABLED, "Registration is disabled on this server.");
        }
        String normalizedEmail = EmailAddresses.normalize(email);
        List<FieldErrorItem> passwordErrors = passwordPolicy.violations(password, normalizedEmail).stream()
                .map(message -> new FieldErrorItem("password", message))
                .toList();
        if (!passwordErrors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "One or more fields are invalid.", passwordErrors);
        }
        if (users.existsByEmail(normalizedEmail)) {
            throw emailTaken();
        }

        User user;
        try {
            user = users.saveAndFlush(new User(normalizedEmail, passwordEncoder.encode(password), displayName.strip()));
        } catch (DataIntegrityViolationException ex) {
            // Lost a race with a concurrent registration for the same address.
            throw emailTaken();
        }
        audit.record(AuditEventType.USER_REGISTERED, user.getId(), null);

        AuthenticatedUser principal = AuthenticatedUser.from(user);
        principal.eraseCredentials();
        return UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
    }

    /** Verifies credentials and applies lockout rules. Not transactional: each step commits on its own. */
    public Authentication authenticate(String email, String password) {
        String normalizedEmail = EmailAddresses.normalize(email);
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(normalizedEmail, password));
        } catch (AuthenticationException ex) {
            loginAttempts.recordFailure(normalizedEmail);
            throw invalidCredentials();
        }
        AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
        if (!loginAttempts.completeLogin(principal.id())) {
            throw invalidCredentials();
        }
        return authentication;
    }

    private static ApiException invalidCredentials() {
        return new ApiException(ErrorCode.BAD_CREDENTIALS, INVALID_CREDENTIALS);
    }

    private static ApiException emailTaken() {
        return new ApiException(ErrorCode.EMAIL_TAKEN, "An account with this e-mail address already exists.");
    }
}
