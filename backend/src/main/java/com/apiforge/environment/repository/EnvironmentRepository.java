package com.apiforge.environment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.environment.domain.Environment;

public interface EnvironmentRepository extends JpaRepository<Environment, UUID> {

    List<Environment> findByProjectIdOrderByNameAsc(UUID projectId);
}
