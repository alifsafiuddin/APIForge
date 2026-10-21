package com.apiforge.coverage.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.coverage.domain.CoverageSnapshot;

public interface CoverageSnapshotRepository extends JpaRepository<CoverageSnapshot, UUID> {

    Optional<CoverageSnapshot> findFirstBySpecificationVersionIdOrderByTakenAtDesc(UUID specificationVersionId);

    List<CoverageSnapshot> findByProjectIdOrderByTakenAtDesc(UUID projectId, Pageable pageable);
}
