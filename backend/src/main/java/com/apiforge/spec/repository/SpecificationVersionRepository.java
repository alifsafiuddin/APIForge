package com.apiforge.spec.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.spec.domain.SpecificationVersion;

public interface SpecificationVersionRepository extends JpaRepository<SpecificationVersion, UUID> {

    List<SpecificationVersion> findByProjectIdOrderByVersionNumberDesc(UUID projectId);
}
