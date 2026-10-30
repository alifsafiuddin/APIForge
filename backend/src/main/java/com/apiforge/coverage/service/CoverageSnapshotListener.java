package com.apiforge.coverage.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.apiforge.coverage.domain.CoverageSnapshot.Trigger;
import com.apiforge.execution.domain.TestRun;
import com.apiforge.execution.service.TestRunFinished;
import com.apiforge.generation.service.TestsGenerated;
import com.apiforge.spec.service.SpecificationActivated;

/**
 * Records coverage snapshots after the changes that move coverage: generated tests, finished test
 * runs and version activations. Only committed changes are recorded, and a failure to record is
 * logged, never propagated to the action that triggered it.
 */
@Component
class CoverageSnapshotListener {

    private static final Logger log = LoggerFactory.getLogger(CoverageSnapshotListener.class);

    private final CoverageService coverage;

    CoverageSnapshotListener(CoverageService coverage) {
        this.coverage = coverage;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(SpecificationActivated event) {
        record(event.projectId(), event.specificationVersionId(), Trigger.VERSION_ACTIVATED, null);
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(TestsGenerated event) {
        record(event.projectId(), event.specificationVersionId(), Trigger.TESTS_GENERATED, null);
    }

    @TransactionalEventListener(fallbackExecution = true)
    void on(TestRunFinished event) {
        if (event.kind() == TestRun.Kind.TEST_RUN && event.specificationVersionId() != null) {
            record(event.projectId(), event.specificationVersionId(), Trigger.RUN_FINISHED, event.runId());
        }
    }

    private void record(UUID projectId, UUID versionId, Trigger trigger, UUID runId) {
        try {
            coverage.snapshot(projectId, versionId, trigger, runId);
        } catch (RuntimeException ex) {
            log.warn("Coverage snapshot ({}) for project {} was not recorded: {}", trigger, projectId, ex.toString());
        }
    }
}
