package com.apiforge.outbound;

import java.time.Duration;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

/**
 * Outbound request policy and limits (docs/SECURITY.md section 6). Every value has a safe upper
 * bound enforced at startup, so configuration cannot disable the protections.
 */
@Validated
@ConfigurationProperties(prefix = "apiforge.outbound")
public record OutboundProperties(
        /* Exact host:port pairs allowed to resolve to loopback/private/CGNAT addresses. Off by default. */
        @DefaultValue("false") boolean trustedTargetsEnabled,
        @NotNull @DefaultValue List<@Valid TrustedTarget> trustedTargets,
        /* Explicit acknowledgement required to use trusted targets with the prod profile. */
        @DefaultValue("false") boolean allowTrustedInProduction,
        @NotNull @DefaultValue("5s") Duration connectTimeout,
        /* Upper bound for an environment's read (response) timeout. */
        @NotNull @DefaultValue("60s") Duration maxReadTimeout,
        /* Whole request including redirects. */
        @NotNull @DefaultValue("30s") Duration totalDeadline,
        @NotNull @DefaultValue("2MB") DataSize maxResponseSize,
        @NotNull @DefaultValue("256KB") DataSize storedBodySize,
        @NotNull @DefaultValue("1MB") DataSize maxRequestSize,
        @DefaultValue("5") @Min(0) @Max(10) int maxRedirects,
        @DefaultValue("100") @Min(10) @Max(500) int maxResponseHeaders,
        @DefaultValue("64") @Min(1) @Max(512) int maxConnections,
        @DefaultValue("2") @Min(1) @Max(16) int maxConcurrentRunsPerUser,
        @DefaultValue("8") @Min(1) @Max(64) int maxConcurrentRuns,
        @DefaultValue("300") @Min(1) @Max(6000) int requestsPerMinutePerUser,
        @DefaultValue("500") @Min(1) @Max(5000) int maxTestsPerRun,
        /* Sent with every request so results can be matched with the target's logs. A dedicated name,
           so it never collides with a header the API under test documents (e.g. X-Request-Id). */
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9-]{1,64}$") @DefaultValue("X-APIForge-Request-Id") String correlationHeader) {

    public record TrustedTarget(@NotBlank String host, @Min(1) @Max(65535) int port) {
    }

    public OutboundProperties {
        requireAtMost("connect-timeout", connectTimeout, Duration.ofSeconds(30));
        requireAtMost("max-read-timeout", maxReadTimeout, Duration.ofMinutes(2));
        requireAtMost("total-deadline", totalDeadline, Duration.ofMinutes(3));
        requireAtMost("max-response-size", maxResponseSize, DataSize.ofMegabytes(20));
        requireAtMost("stored-body-size", storedBodySize, DataSize.ofMegabytes(2));
        requireAtMost("max-request-size", maxRequestSize, DataSize.ofMegabytes(10));
        if (storedBodySize.toBytes() > maxResponseSize.toBytes()) {
            throw new IllegalArgumentException("apiforge.outbound.stored-body-size cannot exceed max-response-size");
        }
        trustedTargets = List.copyOf(trustedTargets);
    }

    private static void requireAtMost(String name, Duration value, Duration max) {
        if (value.isNegative() || value.isZero() || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("apiforge.outbound." + name + " must be between 1ms and " + max);
        }
    }

    private static void requireAtMost(String name, DataSize value, DataSize max) {
        if (value.toBytes() <= 0 || value.toBytes() > max.toBytes()) {
            throw new IllegalArgumentException("apiforge.outbound." + name + " must be between 1 byte and " + max);
        }
    }
}
