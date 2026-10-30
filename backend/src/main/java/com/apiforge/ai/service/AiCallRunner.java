package com.apiforge.ai.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.apiforge.ai.AiProperties;
import com.apiforge.ai.client.AiFailure;
import com.apiforge.ai.client.AiFailure.Kind;
import com.apiforge.ai.client.OllamaGateway;
import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;
import com.apiforge.common.web.FixedWindowRateLimiter;

/**
 * Runs one model call with the limits of {@link AiProperties}: per-user rate limit, prompt size
 * limit, a bounded number of concurrent calls, and a bounded number of attempts (only overload and
 * malformed output are retried; timeouts and unavailability are not). Records timing and sizes for
 * the audit trail, never the prompt itself (only its SHA-256).
 */
@Component
public class AiCallRunner {

    private static final Logger log = LoggerFactory.getLogger("com.apiforge.ai");
    private static final Duration OVERLOAD_PAUSE = Duration.ofSeconds(2);

    /** What is known about a call, successful or not; safe to audit and log. */
    public static final class Metrics {
        String task;
        String model;
        int promptChars;
        String promptSha256;
        int responseChars;
        int attempts;
        long latencyMs;
        Integer outputTokens;

        public long latencyMs() {
            return latencyMs;
        }

        public String model() {
            return model;
        }

        public int attempts() {
            return attempts;
        }

        Map<String, Object> toAudit(String outcome) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("task", task);
            details.put("model", model);
            details.put("outcome", outcome);
            details.put("attempts", attempts);
            details.put("latencyMs", latencyMs);
            details.put("promptChars", promptChars);
            details.put("promptSha256", promptSha256);
            details.put("responseChars", responseChars);
            if (outputTokens != null) {
                details.put("outputTokens", outputTokens);
            }
            return details;
        }
    }

    private final OllamaGateway gateway;
    private final AiProperties properties;
    private final AuditService audit;
    private final AiStatusService status;
    private final Semaphore slots;
    private final FixedWindowRateLimiter limiter;

    public AiCallRunner(OllamaGateway gateway, AiProperties properties, AuditService audit, AiStatusService status,
            Clock clock) {
        this.gateway = gateway;
        this.properties = properties;
        this.audit = audit;
        this.status = status;
        this.slots = new Semaphore(properties.maxConcurrentRequests(), true);
        this.limiter = new FixedWindowRateLimiter(properties.requestsPerMinutePerUser(), Duration.ofMinutes(1), clock);
    }

    /**
     * @param parse turns the raw answer into a validated value; throws INVALID_OUTPUT otherwise
     * @throws AiFailure when no valid answer was obtained
     */
    public <T> T call(UUID userId, String task, String system, String user, Map<String, Object> schema,
            Function<String, T> parse, Metrics metrics) {
        metrics.task = task;
        metrics.model = properties.model();
        metrics.promptChars = system.length() + user.length();
        metrics.promptSha256 = sha256(system + "\n" + user);
        if (!properties.enabled()) {
            throw new AiFailure(Kind.DISABLED, "AI is turned off (apiforge.ai.enabled=false).");
        }
        long retryAfter = limiter.tryAcquire(userId.toString());
        if (retryAfter > 0) {
            throw new ApiException(ErrorCode.RATE_LIMITED, "Too many AI requests. Try again in " + retryAfter + " s.",
                    java.util.List.of(), Map.of("retryAfterSeconds", retryAfter));
        }
        if (metrics.promptChars > properties.maxPromptChars()) {
            throw new AiFailure(Kind.TOO_LARGE, "The input for the model has %d characters; the limit is %d."
                    .formatted(metrics.promptChars, properties.maxPromptChars()));
        }
        boolean acquired;
        try {
            acquired = slots.tryAcquire(properties.queueWait().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiFailure(Kind.BUSY, "Interrupted while waiting for the model.");
        }
        if (!acquired) {
            throw new AiFailure(Kind.BUSY, "The model is busy with another request. Try again shortly.");
        }
        long started = System.nanoTime();
        try {
            for (int attempt = 1;; attempt++) {
                metrics.attempts = attempt;
                try {
                    OllamaGateway.Completion completion = gateway.chat(system, user, schema);
                    metrics.model = completion.model() != null ? completion.model() : metrics.model;
                    metrics.responseChars = completion.content() == null ? 0 : completion.content().length();
                    metrics.outputTokens = completion.outputTokens();
                    if ("length".equals(completion.doneReason())) {
                        throw new AiFailure(Kind.INVALID_OUTPUT, "The answer was cut off at the output token limit ("
                                + properties.maxOutputTokens() + ").");
                    }
                    return parse.apply(completion.content());
                } catch (AiFailure failure) {
                    if (failure.kind() == Kind.UNAVAILABLE || failure.kind() == Kind.MODEL_NOT_FOUND) {
                        status.invalidate();
                    }
                    if (!failure.kind().retryable() || attempt >= properties.maxAttempts()) {
                        throw failure;
                    }
                    log.info("ai {} attempt {} failed ({}), retrying", task, attempt, failure.kind());
                    if (failure.kind() == Kind.OVERLOADED) {
                        pause(OVERLOAD_PAUSE.multipliedBy(attempt));
                    }
                }
            }
        } finally {
            metrics.latencyMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            slots.release();
        }
    }

    /** One audit event per request, success or failure, with sizes and timing only. */
    public void audit(AuditEventType type, UUID userId, UUID projectId, Metrics metrics, String outcome,
            Map<String, Object> extra) {
        Map<String, Object> details = metrics.toAudit(outcome);
        details.putAll(extra);
        audit.recordIndependently(type, userId, null, projectId, details);
        log.info("ai {} model={} outcome={} attempts={} latencyMs={} promptChars={} responseChars={}", metrics.task,
                metrics.model, outcome, metrics.attempts, metrics.latencyMs, metrics.promptChars, metrics.responseChars);
    }

    /** The API error for a failed call (503/504/502/413 by kind). */
    public static ApiException toApi(AiFailure failure) {
        return new ApiException(failure.kind().code(), failure.getMessage(), java.util.List.of(),
                Map.of("aiFailure", failure.kind().name()));
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AiFailure(Kind.BUSY, "Interrupted while waiting to retry.");
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", ex);
        }
    }
}
