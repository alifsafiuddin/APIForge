package com.apiforge.execution.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.apiforge.audit.domain.AuditEventType;
import com.apiforge.audit.service.AuditService;
import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.repository.TestResultRepository;
import com.apiforge.execution.repository.TestRunRepository;

/**
 * Persists runs and results while they execute. Every result is stored in its own short
 * transaction as soon as it exists, so progress is visible and nothing is lost when a run stops.
 */
@Service
public class RunRecorder {

    private final TestRunRepository runs;
    private final TestResultRepository results;
    private final AuditService audit;
    private final Clock clock;

    public RunRecorder(TestRunRepository runs, TestResultRepository results, AuditService audit, Clock clock) {
        this.runs = runs;
        this.results = results;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public TestRun create(TestRun run) {
        return runs.saveAndFlush(run);
    }

    @Transactional
    public void start(UUID runId) {
        TestRun run = runs.findById(runId).orElseThrow();
        run.start(Instant.now(clock));
    }

    @Transactional
    public TestResult record(UUID runId, TestResult result) {
        TestResult saved = results.saveAndFlush(result);
        runs.findById(runId).orElseThrow().record(saved.getOutcome());
        return saved;
    }

    @Transactional
    public TestRun finish(UUID runId, TestRun.Status status, String reason) {
        TestRun run = runs.findById(runId).orElseThrow();
        if (!run.getStatus().isFinished()) {
            run.finish(status, reason, Instant.now(clock));
        }
        return run;
    }

    /** A destination refused by the outbound policy is a security event. */
    @Transactional
    public void auditBlocked(UUID userId, UUID projectId, UUID runId, String reason) {
        audit.record(AuditEventType.OUTBOUND_BLOCKED, userId, null, projectId,
                Map.of("runId", runId.toString(), "reason", reason));
    }

    /** Runs that were queued or running when the application stopped can never finish. */
    @Transactional
    public int interruptUnfinished() {
        List<TestRun> unfinished = runs.findByStatusIn(List.of(TestRun.Status.QUEUED, TestRun.Status.RUNNING));
        Instant now = Instant.now(clock);
        unfinished.forEach(run -> run.finish(TestRun.Status.INTERRUPTED,
                "The server stopped before the run finished; results recorded until then are kept.", now));
        return unfinished.size();
    }
}
