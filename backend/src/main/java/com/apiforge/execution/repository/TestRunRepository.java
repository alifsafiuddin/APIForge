package com.apiforge.execution.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.execution.domain.TestRun;

public interface TestRunRepository extends JpaRepository<TestRun, UUID> {

    Page<TestRun> findByProjectId(UUID projectId, Pageable pageable);

    Optional<TestRun> findByIdAndProjectId(UUID id, UUID projectId);
}
