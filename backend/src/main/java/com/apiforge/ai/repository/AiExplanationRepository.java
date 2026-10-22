package com.apiforge.ai.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.apiforge.ai.domain.AiExplanation;

public interface AiExplanationRepository extends JpaRepository<AiExplanation, UUID> {

    Optional<AiExplanation> findFirstByTestResultIdAndProjectIdOrderByCreatedAtDesc(UUID testResultId, UUID projectId);
}
