package com.apiforge.testcase.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.testcase.domain.TestCase;

public interface TestCaseRepository extends JpaRepository<TestCase, UUID>, JpaSpecificationExecutor<TestCase> {

    List<TestCase> findBySuiteIdOrderByNameAsc(UUID suiteId);

    Optional<TestCase> findByIdAndProjectId(UUID id, UUID projectId);

    List<TestCase> findByProjectIdAndIdIn(UUID projectId, Collection<UUID> ids);

    /** Live tests of one origin (archived ones are history, not candidates for regeneration). */
    @Query("""
            select t from TestCase t
            where t.projectId = :projectId
              and t.origin = :origin
              and t.lifecycleStatus <> com.apiforge.testcase.domain.TestCase.LifecycleStatus.ARCHIVED
            """)
    List<TestCase> findLive(@Param("projectId") UUID projectId, @Param("origin") TestCase.Origin origin);

    @Query("""
            select new com.apiforge.testcase.repository.OperationCount(
                t.operationKey,
                count(t),
                sum(case when t.enabled = true then 1 else 0 end),
                sum(case when t.lifecycleStatus = com.apiforge.testcase.domain.TestCase.LifecycleStatus.STALE then 1 else 0 end))
            from TestCase t
            where t.projectId = :projectId
              and t.lifecycleStatus <> com.apiforge.testcase.domain.TestCase.LifecycleStatus.ARCHIVED
            group by t.operationKey
            order by t.operationKey
            """)
    List<OperationCount> countByOperation(@Param("projectId") UUID projectId);

    @Query("""
            select t.suiteId, count(t) from TestCase t
            where t.projectId = :projectId
              and t.lifecycleStatus <> com.apiforge.testcase.domain.TestCase.LifecycleStatus.ARCHIVED
            group by t.suiteId
            """)
    List<Object[]> countBySuite(@Param("projectId") UUID projectId);

    @Query("""
            select count(t) from TestCase t
            where t.suiteId = :suiteId
              and t.lifecycleStatus <> com.apiforge.testcase.domain.TestCase.LifecycleStatus.ARCHIVED
            """)
    long countLiveInSuite(@Param("suiteId") UUID suiteId);

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
