package com.apiforge.testcase.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.testcase.domain.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID> {

    List<TestCase> findBySuiteIdOrderByNameAsc(UUID suiteId);

    /** Active tests whose operation is not in {@code operationKeys} become STALE (kept, not deleted). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TestCase t set t.lifecycleStatus = com.apiforge.testcase.domain.TestCase.LifecycleStatus.STALE
            where t.projectId = :projectId
              and t.lifecycleStatus = com.apiforge.testcase.domain.TestCase.LifecycleStatus.ACTIVE
              and t.operationKey not in :operationKeys
            """)
    int markStaleExcept(@Param("projectId") UUID projectId, @Param("operationKeys") Collection<String> operationKeys);

    /** Stale tests whose operation exists again become ACTIVE. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update TestCase t set t.lifecycleStatus = com.apiforge.testcase.domain.TestCase.LifecycleStatus.ACTIVE
            where t.projectId = :projectId
              and t.lifecycleStatus = com.apiforge.testcase.domain.TestCase.LifecycleStatus.STALE
              and t.operationKey in :operationKeys
            """)
    int reactivate(@Param("projectId") UUID projectId, @Param("operationKeys") Collection<String> operationKeys);
}
