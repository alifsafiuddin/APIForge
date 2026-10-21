package com.apiforge.support;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.apiforge.execution.domain.TestResult;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.repository.TestResultRepository;
import com.apiforge.execution.repository.TestRunRepository;

/**
 * Seeds execution history directly through repositories. The execution engine that creates
 * runs arrives in a later phase; the read API and its authorization are tested now.
 */
@Component
public class HistoryFixtures {

    private final TestRunRepository runs;
    private final TestResultRepository results;

    public HistoryFixtures(TestRunRepository runs, TestResultRepository results) {
        this.runs = runs;
        this.results = results;
    }

    /** A completed run with one passed and one errored result. */
    public SeededRun completedRun(UUID projectId, UUID userId) {
        // Persist first: created_at is assigned on insert and a run cannot start before it exists.
        TestRun run = runs.saveAndFlush(
                new TestRun(projectId, null, "staging", "https://staging.example.test", userId, 2));
        Instant now = run.getCreatedAt().plusMillis(10);
        run.start(now);
        run.record(TestResult.Outcome.PASSED);
        run.record(TestResult.Outcome.ERROR);
        run.finish(TestRun.Status.COMPLETED, now.plusSeconds(1));
        UUID runId = runs.saveAndFlush(run).getId();

        TestResult passed = results.saveAndFlush(TestResult.evaluated(runId, null, "GET /pets",
                Map.of("name", "List pets"), Map.of("method", "GET", "url", "https://staging.example.test/pets"),
                new TestResult.ResponseData(200, Map.of("content-type", "application/json"), "[]", 2, false,
                        "application/json", 30),
                TestResult.Outcome.PASSED, List.of(Map.of("name", "status", "state", "PASS")), 42, now));
        TestResult errored = results.saveAndFlush(TestResult.failed(runId, null, "POST /pets",
                Map.of("name", "Create pet"), Map.of("method", "POST", "url", "https://staging.example.test/pets"),
                TestResult.ErrorKind.TIMEOUT, "Timed out after 15000 ms", 15000, now.plusMillis(500)));
        return new SeededRun(runId, passed.getId(), errored.getId());
    }

    public record SeededRun(UUID runId, UUID passedResultId, UUID errorResultId) {
    }
}
