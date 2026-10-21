package com.apiforge.execution.service;

import java.util.UUID;

import com.apiforge.execution.domain.TestRun;

/** Published when a run reaches a final status. */
public record TestRunFinished(UUID projectId, UUID runId, TestRun.Kind kind, UUID specificationVersionId) {
}
