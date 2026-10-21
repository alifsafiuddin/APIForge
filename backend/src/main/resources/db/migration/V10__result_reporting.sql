-- Contract validation and reporting (Phase 6).

-- Result lists of a run are filtered by outcome.
CREATE INDEX ix_test_results_run_outcome ON test_results (test_run_id, outcome, executed_at);

-- Time from the start of the request until the response headers arrived (the rest of duration_ms is the body).
ALTER TABLE test_results ADD COLUMN time_to_headers_ms integer;
ALTER TABLE test_results ADD CONSTRAINT ck_test_results_time_to_headers
    CHECK (time_to_headers_ms IS NULL OR (time_to_headers_ms >= 0 AND (duration_ms IS NULL OR time_to_headers_ms <= duration_ms)));
