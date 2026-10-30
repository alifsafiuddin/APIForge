package com.apiforge.execution.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.execution.domain.TestRun;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    /**
     * Runs of a project; each filter applies only when given. {@code failing} true keeps runs with a
     * failed or errored test, false keeps completed runs without any.
     */
    @Query("""
            select r from TestRun r where r.projectId = :projectId
              and (:kind is null or r.kind = :kind)
              and (:status is null or r.status = :status)
              and (:failing is null
                   or (:failing = true and r.failedCount + r.errorCount > 0)
                   or (:failing = false and r.failedCount + r.errorCount = 0
                       and r.status = com.apiforge.execution.domain.TestRun.Status.COMPLETED))
            """)
    Page<TestRun> search(@Param("projectId") UUID projectId, @Param("kind") TestRun.Kind kind,
            @Param("status") TestRun.Status status, @Param("failing") Boolean failing, Pageable pageable);

    Optional<TestRun> findByIdAndProjectId(UUID id, UUID projectId);

    List<TestRun> findByStatusIn(Collection<TestRun.Status> statuses);

    /** The run of the same kind created just before {@code createdAt} (the default comparison baseline). */
    Optional<TestRun> findFirstByProjectIdAndKindAndCreatedAtLessThanOrderByCreatedAtDesc(UUID projectId,
            TestRun.Kind kind, Instant createdAt);
}
