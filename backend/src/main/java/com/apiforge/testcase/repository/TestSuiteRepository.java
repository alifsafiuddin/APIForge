package com.apiforge.testcase.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.testcase.domain.TestSuite;

public interface TestSuiteRepository extends JpaRepository<TestSuite, UUID> {

    List<TestSuite> findByProjectIdOrderByNameAsc(UUID projectId);

    Optional<TestSuite> findByIdAndProjectId(UUID id, UUID projectId);

    Optional<TestSuite> findFirstByProjectIdAndKind(UUID projectId, TestSuite.Kind kind);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);
}
