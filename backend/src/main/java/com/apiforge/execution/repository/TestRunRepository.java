package com.apiforge.execution.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.execution.domain.TestRun;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    Page<TestRun> findByProjectId(UUID projectId, Pageable pageable);

    Optional<TestRun> findByIdAndProjectId(UUID id, UUID projectId);

    List<TestRun> findByStatusIn(Collection<TestRun.Status> statuses);

    /** The run of the same kind created just before {@code createdAt} (the default comparison baseline). */
    Optional<TestRun> findFirstByProjectIdAndKindAndCreatedAtLessThanOrderByCreatedAtDesc(UUID projectId,
            TestRun.Kind kind, Instant createdAt);
}
