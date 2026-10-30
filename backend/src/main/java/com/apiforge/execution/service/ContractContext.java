package com.apiforge.execution.service;

import java.util.Map;

import com.apiforge.validation.ContractValidator;
import com.apiforge.validation.ContractValidator.DocumentedOperation;

/**
 * The contract a run validates against: the operations and component schemas of the specification
 * version that was active when the run started (recorded on the run).
 */
record ContractContext(ContractValidator validator, Map<String, DocumentedOperation> operations) {

    static ContractContext empty() {
        return new ContractContext(new ContractValidator(Map.of()), Map.of());
    }

    /** By operation key (tests), else by matching the concrete path (console requests); may be {@code null}. */
    DocumentedOperation find(String operationKey, String method, String path) {
        DocumentedOperation byKey = operations.get(operationKey);
        return byKey != null ? byKey : ContractValidator.match(method, path, operations.values()).orElse(null);
    }
}
