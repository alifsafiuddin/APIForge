package com.apiforge.auth.service;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import com.apiforge.auth.domain.User;

/**
 * The principal stored in the HTTP session. The password hash is present only while
 * authentication is in progress and is erased afterwards ({@link CredentialsContainer}).
 *
 * <p>Account locking is deliberately not reported through {@link #isAccountNonLocked()}: Spring
 * would then reject the attempt before verifying the password, and the faster response would
 * reveal that the account exists. The lock is enforced after password verification instead.
 */
public final class AuthenticatedUser implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final List<GrantedAuthority> AUTHORITIES = List.of(new SimpleGrantedAuthority("ROLE_USER"));

    private final UUID id;
    private final String email;
    private final String displayName;
    private final boolean enabled;
    private String passwordHash;

    public AuthenticatedUser(UUID id, String email, String displayName, boolean enabled, String passwordHash) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.enabled = enabled;
        this.passwordHash = passwordHash;
    }

    static AuthenticatedUser from(User user) {
        return new AuthenticatedUser(user.getId(), user.getEmail(), user.getDisplayName(), user.isEnabled(),
                user.getPasswordHash());
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    public String displayName() {
        return displayName;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return AUTHORITIES;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "AuthenticatedUser[id=" + id + "]";
    }
}
