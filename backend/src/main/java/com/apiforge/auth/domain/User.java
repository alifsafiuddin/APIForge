package com.apiforge.auth.domain;

import java.time.Duration;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.apiforge.common.persistence.AuditableEntity;

/** An APIForge account. Only the encoded password hash is ever stored. */
@Entity
@Table(name = "users")
public class User extends AuditableEntity {

    @Column(name = "email", nullable = false, columnDefinition = "citext")
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "failed_login_count", nullable = false)
    private int failedLoginCount;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** When the password last changed; sessions signed in earlier are ended. */
    @Column(name = "credentials_changed_at")
    private Instant credentialsChangedAt;

    protected User() {
    }

    public User(String email, String passwordHash, String displayName) {
        this.email = email;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
    }

    public boolean isLockedAt(Instant now) {
        return lockedUntil != null && lockedUntil.isAfter(now);
    }

    /**
     * Records a failed sign-in. Returns {@code true} when this failure locks the account; the
     * counter then restarts so the user gets a fresh set of attempts after the lock expires.
     */
    public boolean recordFailedLogin(Instant now, int maxFailedAttempts, Duration lockoutDuration) {
        failedLoginCount++;
        if (failedLoginCount >= maxFailedAttempts) {
            failedLoginCount = 0;
            lockedUntil = now.plus(lockoutDuration);
            return true;
        }
        return false;
    }

    public void rename(String newDisplayName) {
        this.displayName = newDisplayName;
    }

    public void changePassword(String newPasswordHash, Instant now) {
        this.passwordHash = newPasswordHash;
        this.credentialsChangedAt = now;
    }

    public void recordSuccessfulLogin(Instant now) {
        failedLoginCount = 0;
        lockedUntil = null;
        lastLoginAt = now;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getFailedLoginCount() {
        return failedLoginCount;
    }

    public Instant getLockedUntil() {
        return lockedUntil;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public Instant getCredentialsChangedAt() {
        return credentialsChangedAt;
    }
}
