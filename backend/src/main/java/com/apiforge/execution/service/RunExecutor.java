package com.apiforge.execution.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.apiforge.common.web.FixedWindowRateLimiter;
import com.apiforge.environment.domain.Environment.ExecutionPolicy;
import com.apiforge.environment.service.EnvironmentViews.ExecutionEnvironment;
import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestResult.ErrorKind;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.outbound.Cancellation;
import com.apiforge.outbound.OutboundHttpClient;
import com.apiforge.outbound.OutboundProperties;
import com.apiforge.outbound.OutboundResult;
import com.apiforge.testcase.domain.Expectations;
import com.apiforge.testcase.domain.RequestTemplate;
import com.apiforge.testcase.domain.TestCase.RiskLevel;

/**
 * Executes runs on a bounded pool. Limits: concurrent runs per user and overall, requests per
 * minute per user (runs wait, ad hoc requests are refused), tests per run. Each run can be
 * cancelled, which also aborts the request in flight.
 */
@Component
public class RunExecutor implements DisposableBean {

    /** One test of a run, copied when the run starts so later edits do not change it. */
    public record Item(UUID testCaseId, String operationKey, String method, String path, RiskLevel risk,
            Map<String, Object> testSnapshot, RequestTemplate request, Expectations expectations) {
    }

    record Plan(UUID runId, UUID userId, UUID projectId, ExecutionEnvironment environment, List<Item> items) {
    }

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private final OutboundHttpClient client;
    private final RunRecorder recorder;
    private final OutboundProperties properties;
    private final Clock clock;
    private final FixedWindowRateLimiter requestLimiter;
    private final ExecutorService pool;
    private final Map<UUID, AtomicInteger> activePerUser = new ConcurrentHashMap<>();
    private final Map<UUID, Cancellation> activeRuns = new ConcurrentHashMap<>();

    public RunExecutor(OutboundHttpClient client, RunRecorder recorder, OutboundProperties properties, Clock clock) {
        this.client = client;
        this.recorder = recorder;
        this.properties = properties;
        this.clock = clock;
        this.requestLimiter = new FixedWindowRateLimiter(properties.requestsPerMinutePerUser(), Duration.ofMinutes(1),
                clock);
        this.pool = Executors.newFixedThreadPool(properties.maxConcurrentRuns(),
                Thread.ofVirtual().name("test-run-", 0).factory());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void interruptLeftovers() {
        int interrupted = recorder.interruptUnfinished();
        if (interrupted > 0) {
            log.warn("Marked {} unfinished run(s) from a previous process as INTERRUPTED", interrupted);
        }
    }

    /** Takes one of the user's concurrent-execution slots; {@code false} when all are in use. */
    boolean reserve(UUID userId) {
        AtomicBoolean reserved = new AtomicBoolean();
        activePerUser.compute(userId, (id, count) -> {
            int current = count == null ? 0 : count.get();
            if (current >= properties.maxConcurrentRunsPerUser()) {
                return count;
            }
            reserved.set(true);
            AtomicInteger next = count == null ? new AtomicInteger() : count;
            next.incrementAndGet();
            return next;
        });
        return reserved.get();
    }

    void release(UUID userId) {
        activePerUser.computeIfPresent(userId, (id, count) -> count.decrementAndGet() <= 0 ? null : count);
    }

    /** Queues a run whose slot was reserved; the slot is released when it ends. */
    void submit(Plan plan) {
        Cancellation cancellation = new Cancellation();
        activeRuns.put(plan.runId(), cancellation);
        pool.execute(() -> run(plan, cancellation));
    }

    /** @return whether the run was active (queued or running) and is now being cancelled */
    boolean cancel(UUID runId) {
        Cancellation cancellation = activeRuns.get(runId);
        if (cancellation == null) {
            return false;
        }
        cancellation.cancel();
        return true;
    }

    /** Requests per minute for a single synchronous request: refused instead of waiting. */
    long tryAcquireRequest(UUID userId) {
        return requestLimiter.tryAcquire(userId.toString());
    }

    private void run(Plan plan, Cancellation cancellation) {
        try {
            recorder.start(plan.runId());
            boolean blockedAudited = false;
            for (Item item : plan.items()) {
                TestResult result;
                if (cancellation.isCancelled()) {
                    result = skipped(plan.runId(), item, "The run was cancelled before this test was executed.");
                } else {
                    if (!awaitPermit(plan.userId(), cancellation)) {
                        result = skipped(plan.runId(), item, "The run was cancelled before this test was executed.");
                    } else {
                        result = execute(plan.runId(), plan.environment(), item, cancellation);
                    }
                }
                recorder.record(plan.runId(), result);
                if (!blockedAudited && result.getErrorKind() == ErrorKind.BLOCKED_BY_POLICY) {
                    recorder.auditBlocked(plan.userId(), plan.projectId(), plan.runId(), result.getErrorMessage());
                    blockedAudited = true;
                }
            }
            boolean cancelled = cancellation.isCancelled();
            recorder.finish(plan.runId(), cancelled ? TestRun.Status.CANCELLED : TestRun.Status.COMPLETED,
                    cancelled ? "Cancelled by the user; tests not yet executed were skipped." : null);
        } catch (RuntimeException ex) {
            log.error("Run {} stopped unexpectedly", plan.runId(), ex);
            recorder.finish(plan.runId(), TestRun.Status.INTERRUPTED, "The run stopped because of an internal error.");
        } finally {
            activeRuns.remove(plan.runId());
            release(plan.userId());
        }
    }

    /** Waits for the user's request budget; returns {@code false} if the run is cancelled meanwhile. */
    private boolean awaitPermit(UUID userId, Cancellation cancellation) {
        while (requestLimiter.tryAcquire(userId.toString()) > 0) {
            if (cancellation.isCancelled()) {
                return false;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return !cancellation.isCancelled();
    }

    /** Executes one item and builds its (redacted) result; never throws for target-side problems. */
    TestResult execute(UUID runId, ExecutionEnvironment environment, Item item, Cancellation cancellation) {
        String correlationId = UUID.randomUUID().toString();
        if (!allows(environment.executionPolicy(), item.risk())) {
            return skipped(runId, item, "Environment '%s' only allows %s requests (policy %s); this %s test was not sent."
                    .formatted(environment.name(), allowedDescription(environment.executionPolicy()),
                            environment.executionPolicy(), item.risk()));
        }
        RequestFactory.Prepared prepared;
        try {
            prepared = new RequestFactory(environment, properties.correlationHeader())
                    .prepare(item.method(), item.path(), item.request(), correlationId);
        } catch (RequestFactory.UnresolvedVariableException ex) {
            return TestResult.failed(runId, item.testCaseId(), item.operationKey(), item.testSnapshot(),
                    minimalSnapshot(item, correlationId), ErrorKind.VARIABLE_UNRESOLVED, ex.getMessage(), 0,
                    Instant.now(clock)).correlatedWith(correlationId);
        }
        OutboundResult outcome;
        MDC.put("requestId", correlationId);
        try {
            outcome = client.execute(prepared.request(), cancellation);
        } finally {
            MDC.remove("requestId");
        }
        Redactor redactor = prepared.redactor();
        Map<String, Object> requestSnapshot = new LinkedHashMap<>(prepared.snapshot());
        if (!outcome.redirects().isEmpty()) {
            requestSnapshot.put("redirects", outcome.redirects().stream().map(redactor::url).toList());
        }
        Instant now = Instant.now(clock);
        if (outcome instanceof OutboundResult.Failure failure) {
            return TestResult.failed(runId, item.testCaseId(), item.operationKey(), item.testSnapshot(), requestSnapshot,
                    errorKind(failure.kind()), redactor.text(failure.message()), (int) failure.durationMs(), now)
                    .correlatedWith(correlationId);
        }
        OutboundResult.Response response = (OutboundResult.Response) outcome;
        String text = decode(response.body(), response.contentType());
        OutcomeEvaluator.Evaluation evaluation = OutcomeEvaluator.evaluate(item.expectations(), response.status(),
                response.contentType(), text, response.truncated());
        String stored = redactor.text(text);
        boolean storedTruncated = false;
        long storedLimit = properties.storedBodySize().toBytes();
        if (stored != null && stored.length() > storedLimit) {
            stored = stored.substring(0, (int) storedLimit);
            storedTruncated = true;
        }
        TestResult.ResponseData data = new TestResult.ResponseData(response.status(), redactor.headers(response.headers()),
                stored, response.sizeBytes(), response.truncated() || storedTruncated,
                response.contentType() == null ? null : truncate(response.contentType(), 255));
        return TestResult.evaluated(runId, item.testCaseId(), item.operationKey(), item.testSnapshot(), requestSnapshot,
                data, evaluation.outcome(), evaluation.checks(), (int) response.durationMs(), now)
                .correlatedWith(correlationId);
    }

    private TestResult skipped(UUID runId, Item item, String reason) {
        return TestResult.skipped(runId, item.testCaseId(), item.operationKey(), item.testSnapshot(),
                minimalSnapshot(item, null), reason, Instant.now(clock));
    }

    private static Map<String, Object> minimalSnapshot(Item item, String correlationId) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("method", item.method());
        snapshot.put("path", item.path());
        if (correlationId != null) {
            snapshot.put("correlationId", correlationId);
        }
        return snapshot;
    }

    static boolean allows(ExecutionPolicy policy, RiskLevel risk) {
        return switch (policy) {
            case READ_ONLY -> risk == RiskLevel.READ_ONLY;
            case ALLOW_MUTATING -> risk != RiskLevel.DESTRUCTIVE;
            case ALLOW_DESTRUCTIVE -> true;
        };
    }

    private static String allowedDescription(ExecutionPolicy policy) {
        return switch (policy) {
            case READ_ONLY -> "read-only (GET, HEAD, OPTIONS)";
            case ALLOW_MUTATING -> "read-only and mutating";
            case ALLOW_DESTRUCTIVE -> "all";
        };
    }

    private static ErrorKind errorKind(OutboundResult.Kind kind) {
        return switch (kind) {
            case BLOCKED_BY_POLICY -> ErrorKind.BLOCKED_BY_POLICY;
            case REDIRECT_BLOCKED -> ErrorKind.REDIRECT_BLOCKED;
            case DNS_FAILURE -> ErrorKind.DNS_FAILURE;
            case CONNECT_FAILED -> ErrorKind.CONNECT_FAILED;
            case TIMEOUT -> ErrorKind.TIMEOUT;
            case TLS_ERROR -> ErrorKind.TLS_ERROR;
            case INVALID_RESPONSE -> ErrorKind.INVALID_RESPONSE;
            case CANCELLED -> ErrorKind.CANCELLED;
            case INTERNAL -> ErrorKind.INTERNAL;
        };
    }

    /**
     * The body as text when it is textual (by Content-Type, or valid UTF-8 without one);
     * {@code null} for empty or binary bodies, which are not stored.
     */
    static String decode(byte[] body, String contentType) {
        if (body == null || body.length == 0) {
            return null;
        }
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        boolean textual = type.startsWith("text/") || type.contains("json") || type.contains("xml")
                || type.contains("x-www-form-urlencoded") || type.contains("javascript") || type.contains("yaml");
        if (!textual && !type.isEmpty()) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
        } catch (CharacterCodingException ex) {
            return textual ? new String(body, StandardCharsets.UTF_8) : null;
        }
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    @Override
    public void destroy() {
        activeRuns.values().forEach(Cancellation::cancel);
        pool.shutdownNow();
    }
}
