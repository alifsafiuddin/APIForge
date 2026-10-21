package com.apiforge.spec.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.spec.domain.SpecificationVersion;

public interface SpecificationVersionRepository extends JpaRepository<SpecificationVersion, UUID> {

    List<SpecificationVersion> findByProjectIdOrderByVersionNumberDesc(UUID projectId);

    Optional<SpecificationVersion> findByIdAndProjectId(UUID id, UUID projectId);

    @Query("select coalesce(max(v.versionNumber), 0) from SpecificationVersion v where v.projectId = :projectId")
    int findHighestVersionNumber(@Param("projectId") UUID projectId);

    Optional<SpecificationVersion> findFirstByProjectIdAndContentSha256OrderByVersionNumberDesc(UUID projectId,
            String contentSha256);
}
