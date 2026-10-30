package com.apiforge.execution.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.execution.domain.TestResult;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

    Page<TestResult> findByTestRunId(UUID testRunId, Pageable pageable);

    Page<TestResult> findByTestRunIdAndOutcome(UUID testRunId, TestResult.Outcome outcome, Pageable pageable);

    List<TestResult> findByTestRunIdOrderByExecutedAtAsc(UUID testRunId);

    /** Results of test runs (not console requests) on one specification version. */
    @Query("""
            select new com.apiforge.execution.repository.CoverageResult(
                       r.testCaseId, r.operationKey, r.outcome, r.responseStatus, r.executedAt)
            from TestResult r, TestRun run
            where r.testRunId = run.id
              and run.projectId = :projectId
              and run.kind = com.apiforge.execution.domain.TestRun.Kind.TEST_RUN
              and run.specificationVersionId = :versionId
            """)
    List<CoverageResult> findForCoverage(@Param("projectId") UUID projectId, @Param("versionId") UUID versionId);

    /** As {@link #findForCoverage}, limited to runs against one environment. */
    @Query("""
            select new com.apiforge.execution.repository.CoverageResult(
                       r.testCaseId, r.operationKey, r.outcome, r.responseStatus, r.executedAt)
            from TestResult r, TestRun run
            where r.testRunId = run.id
              and run.projectId = :projectId
              and run.kind = com.apiforge.execution.domain.TestRun.Kind.TEST_RUN
              and run.specificationVersionId = :versionId
              and run.environmentId = :environmentId
            """)
    List<CoverageResult> findForCoverageInEnvironment(@Param("projectId") UUID projectId,
            @Param("versionId") UUID versionId, @Param("environmentId") UUID environmentId);
}
