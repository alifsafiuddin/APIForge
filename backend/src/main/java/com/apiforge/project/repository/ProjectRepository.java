package com.apiforge.project.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.apiforge.project.domain.Project;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    /** The only way services load a project: scoped to its owner. */
    Optional<Project> findByIdAndOwnerId(UUID id, UUID ownerId);

    /**
     * @param namePattern a LIKE pattern with %, _ and \ already escaped, or {@code null} for no filter
     */
    @Query("""
            select p from Project p
            where p.ownerId = :ownerId
              and ((:archived = true and p.archivedAt is not null) or (:archived = false and p.archivedAt is null))
              and (:namePattern is null or lower(p.name) like :namePattern escape '\\')
            """)
    Page<Project> search(@Param("ownerId") UUID ownerId, @Param("archived") boolean archived,
            @Param("namePattern") String namePattern, Pageable pageable);

    @Query("""
            select count(p) > 0 from Project p
            where p.ownerId = :ownerId and p.archivedAt is null and lower(p.name) = lower(:name)
              and (:excludeId is null or p.id <> :excludeId)
            """)
    boolean existsActiveName(@Param("ownerId") UUID ownerId, @Param("name") String name,
            @Param("excludeId") UUID excludeId);
}
