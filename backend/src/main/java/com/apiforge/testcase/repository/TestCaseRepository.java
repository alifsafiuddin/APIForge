package com.apiforge.testcase.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.testcase.domain.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    List<TestCase> findBySuiteIdOrderByNameAsc(UUID suiteId);
}
