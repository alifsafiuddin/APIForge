package com.apiforge.execution.repository;

import java.time.Instant;
import java.util.UUID;

import com.apiforge.execution.domain.TestResult;

/** The fields of a test result that coverage needs. */
public record CoverageResult(UUID testCaseId, String operationKey, TestResult.Outcome outcome, Integer responseStatus,
        Instant executedAt) {
}
