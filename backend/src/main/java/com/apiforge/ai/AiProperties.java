package com.apiforge.ai;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Local AI through Ollama (ADR-029). AI is optional: when it is disabled or Ollama is not reachable,
 * every other feature works unchanged. The base URL is operator configuration (never user input),
 * and every limit has an upper bound so a model call cannot run, retry or grow without end.
 */
@Validated
@ConfigurationProperties(prefix = "apiforge.ai")
public record AiProperties(
        @DefaultValue("true") boolean enabled,
        @NotBlank @DefaultValue("http://localhost:11434") String baseUrl,
        /* An Ollama model tag, e.g. qwen2.5:7b. */
        @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$") @DefaultValue("qwen2.5:7b") String model,
        @NotNull @DefaultValue("3s") Duration connectTimeout,
        /* One model call (generation is not streamed, so this bounds the whole answer). */
        @NotNull @DefaultValue("120s") Duration requestTimeout,
        /* The availability check (lists the installed models). */
        @NotNull @DefaultValue("5s") Duration statusTimeout,
        /* Attempts per request, including the first; only overload and malformed output are retried. */
        @DefaultValue("2") @Min(1) @Max(3) int maxAttempts,
        @DefaultValue("1") @Min(1) @Max(8) int maxConcurrentRequests,
        /* How long a request waits for a free slot before it is refused as busy. */
        @NotNull @DefaultValue("2s") Duration queueWait,
        @DefaultValue("10") @Min(1) @Max(120) int requestsPerMinutePerUser,
        /* System and user prompt together; larger inputs are refused before calling the model. */
        @DefaultValue("24000") @Min(2000) @Max(200000) int maxPromptChars,
        @DefaultValue("8192") @Min(2048) @Max(131072) int contextTokens,
        @DefaultValue("1500") @Min(128) @Max(8192) int maxOutputTokens,
        @DefaultValue("0.2") @DecimalMin("0.0") @DecimalMax("1.0") double temperature,
        /* How long Ollama keeps the model loaded after a request (Ollama duration syntax). */
        @NotBlank @Pattern(regexp = "^-?[0-9]{1,5}[smh]?$") @DefaultValue("10m") String keepAlive,
        @DefaultValue("5") @Min(1) @Max(10) int maxSuggestions) {

    public AiProperties {
        URI uri;
        try {
            uri = new URI(baseUrl);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException("apiforge.ai.base-url is not a valid URL");
        }
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "apiforge.ai.base-url must be an http(s) URL with a host and without credentials, query or fragment");
        }
        requireBetween("connect-timeout", connectTimeout, Duration.ofSeconds(30));
        requireBetween("request-timeout", requestTimeout, Duration.ofMinutes(10));
        requireBetween("status-timeout", statusTimeout, Duration.ofSeconds(30));
        requireBetween("queue-wait", queueWait, Duration.ofSeconds(30));
    }

    /** scheme://host:port of the Ollama server (shown in the UI; the URL never carries credentials). */
    public String origin() {
        URI uri = URI.create(baseUrl);
        return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    private static void requireBetween(String name, Duration value, Duration max) {
        if (value.isNegative() || value.isZero() || value.compareTo(max) > 0) {
            throw new IllegalArgumentException("apiforge.ai." + name + " must be between 1ms and " + max);
        }
    }
}
