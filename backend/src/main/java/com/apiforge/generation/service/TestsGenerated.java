package com.apiforge.generation.service;

import java.util.UUID;

/** Published after tests were generated for a project's active specification version. */
public record TestsGenerated(UUID projectId, UUID specificationVersionId) {
}
