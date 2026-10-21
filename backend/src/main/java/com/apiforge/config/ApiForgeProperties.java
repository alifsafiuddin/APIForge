package com.apiforge.config;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Application-specific configuration under the {@code apiforge.*} prefix.
 * Values are validated at startup so misconfiguration fails fast.
 */
@Validated
@ConfigurationProperties(prefix = "apiforge")
public record ApiForgeProperties(@Valid @NotNull @DefaultValue Cors cors, @Valid @NotNull @DefaultValue Auth auth,
        @Valid @NotNull @DefaultValue Specification specification) {

    /**
     * CORS settings for browser clients served from a different origin than the API
     * (the Vite dev server when it is not proxying). Wildcards are rejected because
     * credentials (session cookies) are allowed on cross-origin requests.
     */
    public record Cors(
            @NotNull @DefaultValue
            List<@Pattern(regexp = "^https?://[A-Za-z0-9.\\-]+(:\\d{1,5})?$",
                    message = "must be an explicit http(s) origin without path or wildcard") String> allowedOrigins) {
    }

    /** Authentication and session hardening; see docs/SECURITY.md section 3. */
    public record Auth(
            @DefaultValue("true") boolean registrationEnabled,
            /* bcrypt work factor; tests may lower it, production keeps the default of 12. */
            @DefaultValue("12") @Min(4) @Max(16) int bcryptStrength,
            /* Consecutive failed sign-ins before the account is temporarily locked. */
            @DefaultValue("5") @Min(1) @Max(100) int maxFailedAttempts,
            @NotNull @DefaultValue("15m") Duration lockoutDuration,
            /* Sessions end this long after sign-in even if active (the idle timeout is server.servlet.session.timeout). */
            @NotNull @DefaultValue("12h") Duration absoluteSessionTimeout,
            /* Per client IP, per minute. */
            @DefaultValue("10") @Min(1) int loginRequestsPerMinute,
            @DefaultValue("5") @Min(1) int registrationRequestsPerMinute,
            /* Marks session and CSRF cookies Secure; must be true whenever the app is served over HTTPS. */
            @DefaultValue("false") boolean secureCookies) {
    }

    /**
     * Limits applied to imported OpenAPI documents (docs/SECURITY.md section 5). They bound memory
     * and CPU use for untrusted input; documents beyond them are rejected with a clear message.
     */
    public record Specification(
            /* Maximum document size; uploads and pasted text alike. */
            @NotNull @DefaultValue("5MB") DataSize maxSize,
            @DefaultValue("2000") @Min(1) @Max(20000) int maxOperations,
            /* Maximum nesting depth of objects and arrays in the document. */
            @DefaultValue("100") @Min(10) @Max(1000) int maxNestingDepth,
            /* YAML anchors/aliases referencing collections (protects against "billion laughs"). */
            @DefaultValue("50") @Min(0) @Max(1000) int maxYamlAliases) {
    }
}
