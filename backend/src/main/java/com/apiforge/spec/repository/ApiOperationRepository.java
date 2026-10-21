package com.apiforge.spec.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.spec.domain.ApiOperation;

public interface ApiOperationRepository extends JpaRepository<ApiOperation, UUID> {

    List<ApiOperation> findBySpecificationVersionIdOrderBySortOrderAsc(UUID specificationVersionId);

    Optional<ApiOperation> findByIdAndSpecificationVersionId(UUID id, UUID specificationVersionId);

    @Query("select o.operationKey from ApiOperation o where o.specificationVersionId = :versionId")
    List<String> findOperationKeys(@Param("versionId") UUID specificationVersionId);
}
