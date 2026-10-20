package com.apiforge.environment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.environment.domain.EnvironmentVariable;

public interface EnvironmentVariableRepository extends JpaRepository<EnvironmentVariable, UUID> {

    List<EnvironmentVariable> findByEnvironmentIdOrderByNameAsc(UUID environmentId);
}
