# Project Status

**Current phase**: Phase 7 — Coverage and missing-scenario analysis — **complete** (2026-10-22)
**Next phase**: decided by the next phase brief (planned: AI through a local model)
**Runnable today**: sign-in and private projects; OpenAPI import and explorer; deterministic test generation and curation; environments with encrypted secrets, test runs and console requests through a guarded outbound client; contract validation with run reports and comparisons; **specification coverage with defined metrics, gaps, drill-down and changes between versions**. Not yet: AI.

## Phase 7 summary

### Delivered

| Requirement | Implementation | Where |
|---|---|---|
| Total discovered operations; operations with and without tests | Operations of the active version; "with tests" = at least one enabled (enabled, approved, active) test | `coverage/service/CoverageCalculator` (ADR-028) |
| Total enabled test cases; executed test cases | Distinct enabled tests; executed = distinct tests with an execution attempt in a test run on this version (skipped tests are not executed) | `CoverageCalculator` |
| Passed, failed, errored, skipped and inconclusive tests | Latest attempted outcome per distinct test (an attempt beats a newer skip; skip-only = skipped; no result = not run), all environments or one | `CoverageCalculator.Latest`, `TestResultRepository.findForCoverage*` |
| Documented response codes exercised | Per documented response (code, class, default): *targeted* by an expected status, *exercised* when a run received a matching status (exact → class → default); undocumented statuses received are listed | `CoverageCalculator.documentedResponse/targetedResponses` |
| Known schema branches or constraints covered | Request constraints = the single-fault and boundary scenarios the generator derives (required, type, bounds, lengths, item counts, enum, content type, accepted boundaries), covered by an enabled test of that scenario; oneOf/anyOf alternatives in request bodies exercised by test bodies; scenarios the generator cannot build listed with the reason | `ScenarioKind`, `SchemaBranches` |
| Define every metric clearly | Every metric carries its definition in the API and on the page; ADR-028 states them in full | `CoverageCalculator.metrics`, `CoveragePage` |
| Endpoint coverage separate from the pass rate | Metrics are grouped as *Test design* and *Execution*; the pass rate is labelled "an execution result, not coverage" | `CoverageViews.Group`, `CoveragePage` |
| No claim of code coverage | The page states that this is specification coverage and that APIForge cannot see the target's source code | `CoveragePage` |
| Endpoints without tests; documented responses not exercised; common missing negative scenarios | Gaps panel: untested operations, unexercised responses (and whether a test expects them), missing kinds (credentials, missing required input, wrong type, out of range, outside an enum, unsupported content type) with the operations they are missing for | `CoverageCalculator.gaps` |
| Track changes when a specification version changes | Operations added, removed (stale tests), changed (parameters, request body, responses, security, referenced schemas) with tests generated from an earlier version; summary snapshots after generation, test runs and activation (V11) compared with the live numbers; history | `SpecificationDiff`, `CoverageSnapshotListener`, `coverage_snapshots` |
| Filters and drill-down | Environment filter for execution; operation table filtered by gap (no tests, responses, negatives, constraints, failing, undocumented statuses), method, tag and search; per-operation sheet (responses, negative kinds, scenarios with "only missing", skipped scenarios, branches, tests with duplicates and outcomes) | `CoveragePage`, `OperationCoverageSheet`, `VersionChangesView` |
| Avoid double-counting duplicated test cases | Enabled tests of one operation with the same request (canonical JSON) and expected statuses count once; their outcome is the latest attempt of any copy; a test's repeated runs count once | `CoverageService.fingerprint` |
| Backend tests for all metric calculations | `CoverageCalculatorTest` (26, every metric on a hand-worked project), `CoverageRulesTest` (29), `CoverageIntegrationTest` (3) | docs/TESTING.md |

### Verification (executed 2026-10-22; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Method | Result |
|---|---|---|
| Backend build and tests (clean checkout) | `git checkout-index` of the staged tree into an empty directory, `mvnw verify` | ✅ **745 tests, 0 failures, 0 errors, 0 skipped**; compiled with all lint warnings as errors; enforcer and architecture rules pass; line coverage 92.8% (5775/6220), branch 79.0%; `coverage` package 96.2% lines, 85.0% branches |
| Frontend (clean checkout) | `npm ci`, `npm run check`, coverage, `npm run build` | ✅ 0 vulnerabilities; 0 lint findings; tsc clean; **95/95 tests**; lines 88.5%, branches 74.5%; build without warnings |
| Migration on existing data | Backend jar started against the Phase 6 dev database | ✅ V10 → V11 applied |
| **Completion check**: a specification with known endpoints and scenarios; metrics calculated by hand; the dashboard must match | `samples/coverage-lab-openapi.yaml` (5 operations) against the Warehouse Lab server, driven in headless Microsoft Edge (import and generation through the UI). Every expected number was worked out from the specification and the documented generation rules **before** running (written down in the check script and in samples/README.md), then the documented steps were performed: 5 tests disabled, one test turned into a duplicate, a read-only run, then a DELETE-only run in a destructive environment, then version 2 imported | ✅ **48/48 checks, run three times on fresh projects.** Generated 30 = 1+6+14+6+3; operations with tests 4 of 5; distinct enabled tests 24 (1 duplicate not counted); responses targeted 12 of 14; request constraints 15 of 17; branches 0 of 0; negative kinds 10 of 12; executed 12 of 24, passed 12 of 12, 12 skipped, 0 not run; responses exercised 7 of 14; the 7 unexercised responses, the untested `/reports`, the 2 missing negative kinds and all 5 operation rows exactly as calculated; per environment: read-only 6 executed / 18 skipped / 4 responses, destructive 6 executed / 18 not run / 3 responses. Version 2: added `GET /products` (no tests), removed `/reports` (3 stale tests), `DELETE` changed (responses; 6 tests from version 1), 3 unchanged; baseline = the snapshot after the DELETE run with the same numbers; version 2 shows 0 executed (runs on version 1 do not count); history activation → generation → 2 runs → activation. UI: the same numbers on the cards (80%, 88%, 12 of 12), filters, drill-down, version changes; no horizontal scrolling at 390 px; no JavaScript errors |

### Defects found and fixed during this phase

- A test without results crashed the calculator (`Map.merge` with a null value); found by the first unit test run.
- Branches of recursive schemas were listed again at every nesting level; cycle detection now keys on where a schema is defined.
- "Operations without tests" had a one-line definition; it now says what it means and where the list is.
- Completion-check script issues (not product defects): a filter assertion read the table before it re-rendered, and a text check matched `/reports` in the description.

### Known limitations

- Coverage is of the **active** version. Older versions are visible through their snapshots (summary numbers), not as full reports, because tests are updated in place when a new version is generated.
- Request constraints are those APIForge's generator can test one at a time; `pattern`, formats, `minProperties`/`additionalProperties` and the contents of oneOf/anyOf alternatives are not in the denominator (the drill-down lists what was skipped and why). Response schema branches are not tracked.
- A test written by hand has no scenario key, so it counts for operation, response and duplicate metrics but not for constraint or negative-kind coverage.
- Snapshots are written on generation, finished test runs and activation; disabling or editing tests changes the live numbers but writes no snapshot until the next such event.
- The cross-project dashboard planned in API_DESIGN was not part of this brief and is not implemented.
- GitHub CI status for this branch is checked by the user.

## History

- **Phase 6 (2026-10-22)** — Response contract validation (documented status, media type, JSON syntax, body schema with JSON paths, expected and actual), honest outcomes (skipped never counted as passed), run reports with outcome filters, body inspection, timing and run comparison (V10). 687 backend and 90 frontend tests; completion check 27/27 against the Warehouse Lab server with deliberate violations (reported failures equal the server's actual violations). Committed as `476ae9d`.
- **Phase 5 (2026-10-21)** — Secure execution engine (V9): environments with encrypted write-only secrets, a single guarded outbound client (strict URL parsing, DNS validated before connecting, revalidated redirects, limits), asynchronous cancellable runs and a console, redaction everywhere. 624 backend and 85 frontend tests; completion check 25/25 against the local Warehouse Lab server (128 tests, 1 deliberate failure). Committed as `0baf58f`.
- **Phase 4 (2026-10-21)** — Deterministic test generation (V8): validated values, single-fault negatives, explicit expectations, stable scenario identity, idempotent regeneration; test workspace with review, edit, enable/disable and suites. 388 backend and 81 frontend tests; completion check with the Warehouse Lab sample (128 tests, regeneration repeatable). Committed as `58aa5b7`.
- **Phase 3 (2026-10-21)** — OpenAPI 3.0/3.1 import (upload or paste) with strict validation, local-only references, immutable versions and normalized operations (V7); specification explorer. 189 backend and 74 frontend tests; completion check with the official Petstore 3 sample. Committed as `396be8e`.
- **Phase 2 (2026-10-21)** — Database schema (V2–V6), session authentication with CSRF, lockout, rate limiting and audit trail; ownership-based authorization with 404 for foreign resources; projects CRUD; read-only execution history; ArchUnit rules. 129 backend and 51 frontend tests; committed as `0da6635` (branch `phase-2-database-auth`).
- **Phase 1 (2026-10-21)** — Runnable foundation: Spring Boot 4.1.1, PostgreSQL 18.6, Flyway, Actuator health, problem+json errors, request correlation, CORS, React shell, CI workflow. Committed as `fbbd96f`.
- **Phase 0 (2026-10-20)** — Design documents and verified dependency matrix.

## Open risks carried forward

1. **Multi-instance deployment** would need shared rate limits and run coordination (in memory today).
2. **Small-model JSON reliability** for AI features (ADR-019).

## Milestone checklist

- [x] Phase 0 — Design
- [x] Phase 1 — Foundation
- [x] Phase 2 — Database design and authentication
- [x] Phase 3 — OpenAPI import and explorer
- [x] Phase 4 — Deterministic test generation
- [x] Phase 5 — Secure execution engine (environments, secrets, outbound safety, runs, console)
- [x] Phase 6 — Response contract validation and test reporting
- [x] Phase 7 — Coverage and missing-scenario analysis
- [ ] AI (Ollama via Spring AI)
- [ ] Hardening and release (Dockerfiles, nginx, full Compose, Playwright E2E in CI)

## Completion definition tracker

| Criterion | Status |
|---|---|
| Frontend and backend run together | ✅ (dev mode) |
| PostgreSQL persists real application data | ✅ users, projects, specifications, operations, test suites and cases, audit trail |
| Valid OpenAPI spec can be imported and explored | ✅ Phase 3 (verified with the official Petstore 3 document) |
| Real tests generated and executed against an authorized test API | ✅ Phase 5 (Warehouse Lab sample: 128 tests executed) |
| Contract validation produces explainable results | ✅ Phase 6 (path, expected, actual and message per finding; verified against deliberate violations) |
| Test history and coverage persist | ✅ Runs, results and assertions persist (Phases 5–6); coverage is computed from them, with snapshots across versions (Phase 7) |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ✅ incl. 168 outbound security tests and execution ITs |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
