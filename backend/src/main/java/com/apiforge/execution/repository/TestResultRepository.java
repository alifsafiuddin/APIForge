package com.apiforge.execution.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.execution.domain.TestResult;

public interface TestResultRepository extends JpaRepository<TestResult, UUID> {

    Page<TestResult> findByTestRunId(UUID testRunId, Pageable pageable);
}
