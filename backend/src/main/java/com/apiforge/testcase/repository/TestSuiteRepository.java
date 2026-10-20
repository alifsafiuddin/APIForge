package com.apiforge.testcase.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.testcase.domain.TestSuite;

public interface TestSuiteRepository extends JpaRepository<TestSuite, UUID> {

    List<TestSuite> findByProjectIdOrderByNameAsc(UUID projectId);
}
