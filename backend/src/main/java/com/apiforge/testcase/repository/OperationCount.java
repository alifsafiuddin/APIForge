package com.apiforge.testcase.repository;

/** Per-operation counts of non-archived tests (a query projection of {@link TestCaseRepository}). */
public record OperationCount(String operationKey, long total, long enabled, long stale) {
}
