package com.apiforge.spec.service;

import java.util.UUID;

/** Published when a project's active specification version changes (after test lifecycles were updated). */
public record SpecificationActivated(UUID projectId, UUID specificationVersionId) {
}
