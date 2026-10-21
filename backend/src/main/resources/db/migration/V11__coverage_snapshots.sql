-- Coverage and missing-scenario analysis (Phase 7).
--
-- Coverage itself is computed on demand from test_cases and test_results (ADR-013). To show how
-- coverage changed when the specification changed, the summary numbers are recorded after test
-- generation, after a test run and when a version is activated (ADR-028).
CREATE TABLE coverage_snapshots (
    id                       uuid         PRIMARY KEY,
    project_id               uuid         NOT NULL,
    specification_version_id uuid         NOT NULL,
    trigger                  varchar(30)  NOT NULL,
    test_run_id              uuid,
    taken_at                 timestamptz  NOT NULL,
    metrics                  jsonb        NOT NULL,
    CONSTRAINT fk_coverage_snapshots_project FOREIGN KEY (project_id) REFERENCES api_projects (id) ON DELETE CASCADE,
    CONSTRAINT fk_coverage_snapshots_version FOREIGN KEY (specification_version_id, project_id)
        REFERENCES specification_versions (id, project_id) ON DELETE CASCADE,
    CONSTRAINT ck_coverage_snapshots_trigger CHECK (trigger IN ('TESTS_GENERATED', 'RUN_FINISHED', 'VERSION_ACTIVATED')),
    -- Only run snapshots name a run (and lose the reference when the run is deleted).
    CONSTRAINT ck_coverage_snapshots_run CHECK (trigger = 'RUN_FINISHED' OR test_run_id IS NULL),
    CONSTRAINT ck_coverage_snapshots_metrics CHECK (jsonb_typeof(metrics) = 'object')
);

-- A snapshot's run belongs to the same project; deleting the run keeps the snapshot.
ALTER TABLE test_runs ADD CONSTRAINT uq_test_runs_id_project UNIQUE (id, project_id);
ALTER TABLE coverage_snapshots ADD CONSTRAINT fk_coverage_snapshots_run FOREIGN KEY (test_run_id, project_id)
    REFERENCES test_runs (id, project_id) ON DELETE SET NULL (test_run_id);

CREATE INDEX ix_coverage_snapshots_project_taken ON coverage_snapshots (project_id, taken_at DESC);
CREATE INDEX ix_coverage_snapshots_version_taken ON coverage_snapshots (specification_version_id, taken_at DESC);

-- Snapshots are history: only the ON DELETE SET NULL of test_run_id may modify a row.
CREATE FUNCTION prevent_coverage_snapshot_update() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF (to_jsonb(NEW) - 'test_run_id') IS DISTINCT FROM (to_jsonb(OLD) - 'test_run_id') THEN
        RAISE EXCEPTION 'coverage_snapshots rows are immutable' USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_coverage_snapshots_immutable
    BEFORE UPDATE ON coverage_snapshots
    FOR EACH ROW EXECUTE FUNCTION prevent_coverage_snapshot_update();
