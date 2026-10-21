# Project Status

**Current phase**: Phase 6 — Response contract validation and test reporting — **complete** (2026-10-22)
**Next phase**: decided by the next phase brief (planned: coverage and dashboard)
**Runnable today**: sign-in and private projects; OpenAPI import and explorer; deterministic test generation and curation; environments with encrypted secrets, test runs and console requests through a guarded outbound client; **every response validated against the contract (status, media type, JSON, schema) with explainable assertions; run reports with outcome filters, body inspection, timing and run comparison**. Not yet: coverage, AI.

## Phase 6 summary

### Delivered

| Requirement | Implementation | Where |
|---|---|---|
| Compare the actual status with the documented responses | `STATUS_DOCUMENTED`: exact code, then class (`4XX`), then `default`; undocumented → FAIL listing the documented statuses. The test's own expectation stays `STATUS` | `validation/ContractValidator`, `OutcomeEvaluator` |
| Match the content type against documented media types | `CONTENT_TYPE`: exact media type before ranges (`application/*`), parameters ignored; missing or undocumented → FAIL | `ContractValidator.matchingMediaType` |
| Validate the body against the correct response schema: required fields, types, nested objects, arrays, enums, constraints | The shared `com.apiforge.schema` validator in the response direction: all JSON Schema assertion keywords used by OpenAPI 3.0/3.1 (types, required, properties/additionalProperties, items, counts and uniqueness, enum/const, bounds incl. exclusive, exact `multipleOf`, lengths, budgeted `pattern`, allOf/oneOf/anyOf/not), readOnly/writeOnly by direction, 13 asserted formats | `schema/*` (ADR-027) |
| Structured assertion results with a JSON path per body mismatch, expected, actual and message | Each assertion: `name`, `result`, `message`, and `path`, `keyword`, `expected`, `actual` when they apply; one FAIL per violation (up to 50 + a count); all values redacted before storage | `validation/Assertion`, `RunExecutor` |
| Distinguish PASS, FAIL, ERROR, SKIPPED and INCONCLUSIVE | Assertions PASS/FAIL/SKIPPED/INCONCLUSIVE; results PASSED/FAILED/INCONCLUSIVE/ERROR/SKIPPED (any FAIL → FAILED, else any INCONCLUSIVE → INCONCLUSIVE, else PASSED) | `OutcomeEvaluator` |
| Undocumented status codes and missing response schemas handled explicitly | Undocumented status → FAIL + body SKIPPED ("no documented response for status 503"); documented without body or schema, invalid JSON, content-type mismatch, validation off → SKIPPED with the reason; truncated, non-JSON, unsupported keywords → INCONCLUSIVE | `ContractValidator` |
| A skipped validation is never labelled as passed | SKIPPED assertions never count toward PASSED; summaries carry `skippedChecks`; the result list shows "N checks skipped", the result panel counts them separately ("4 passed, 1 skipped (not validated)") and says they are not counted as passed. Generated tests now validate whatever status comes back (generator version 2) | `ExecutionHistoryViews`, `ResultView`, `ResponseCatalog` |
| Execution progress; passed and failed tests | Run page: stacked outcome bar (and accessible progress element), counts, outcome filter (server-side `?outcome=`), paging past 100 results, rows with first failure, failure count, skipped checks and a duration bar; runs list with outcome bars | `RunDetailPage`, `RunsPage` |
| Assertion details; expected versus actual | Assertions table: result, check, JSON path (click to reveal in the body), keyword, expected, actual, message | `ResultView` |
| Response body inspection | JSON tree with failing values marked and absent required properties shown as "missing", raw view, non-JSON and truncated bodies as text | `BodyInspector` |
| Request and response timing | `time_to_headers_ms` recorded by the outbound client next to the total; the result panel shows sent/headers/body/total/finished | V10, `OutboundHttpClient`, `ResultView` |
| Run history and comparisons | `GET …/runs/{id}/comparison[?baseline=]`: previous run of the same kind by default; tests matched by test case, else operation and name; FIXED/REGRESSED/CHANGED/UNCHANGED/NEW/REMOVED; comparison page with baseline picker and links to both results | `ExecutionHistoryService.compare`, `RunComparisonPage` |
| Unit and integration tests for schema validation and status matching | `ContractValidatorTest` (26), `SchemaValidatorTest` (91, +34), `RedactorAndEvaluatorTest` (status matching and outcome rule), `ExecutionIntegrationTest` (contract validation, filters and comparison over HTTP), `SchemaIntegrationTest` (V10 constraint); frontend `RunReporting.test.tsx` | docs/TESTING.md |
| Sample API with valid and invalid responses | `WAREHOUSE_LAB_VIOLATIONS=1` switches on 6 documented, deterministic contract violations; the spec documents the 401/415 responses the server really sends | samples/README.md |

### Verification (executed 2026-10-22; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Method | Result |
|---|---|---|
| Backend build and tests (clean checkout) | `git checkout-index` of the staged tree into an empty directory, `mvnw verify` | ✅ **687 tests, 0 failures, 0 errors, 0 skipped**; compiled with all lint warnings as errors; enforcer rules pass; line coverage 92.4% (5123/5543), branch 78.3%; `validation` 100%, `schema` 92.3%, `execution` 93.2%, `outbound` 90.1%, `generation` 90.8% |
| Frontend (clean checkout) | `npm ci`, `npm run check`, coverage, `npm run build` | ✅ 0 vulnerabilities; 0 lint findings; tsc clean; **90/90 tests**; lines 88.2%, branches 73.4%; build without warnings |
| Migration on existing data | Backend jar started against the Phase 5 dev database | ✅ V9 → V10 applied |
| **Completion check**: execute test cases against a sample API with valid and intentionally invalid responses; reported failures must match the actual contract violations | Headless Microsoft Edge against the running stack (PostgreSQL in Docker, backend jar with the dev profile, Vite). The check script starts `samples/warehouse-lab-server.mjs` itself, imports and generates the Warehouse Lab tests through the UI, and runs all 128 tests twice: once with the server answering per contract, once with `WAREHOUSE_LAB_VIOLATIONS=1`. For **every result** it computes the expected failed assertions (check, JSON path, keyword) from an independent model of the server's documented deviations and compares them with what APIForge reported | ✅ **27/27 checks.** Contract-compliant server: 127 passed, 1 failed; **reported = expected, 2 assertions** (the documented quantity deviation: unexpected 201 *and* `$.lines[0].quantity` `maximum` in its body). Violating server: 115 passed, 13 failed, 0 errors; **reported = expected, 27 assertions, no extra and no missing finding**, covering STATUS, STATUS_DOCUMENTED, CONTENT_TYPE and BODY_SCHEMA type/format/minimum/required/enum/maximum. A test is FAILED exactly when it has a failed assertion; every skipped assertion has a reason (5 and 4 passed tests with skipped checks, flagged in the UI as not counted as passed); every body finding has a path, expected and actual; time to headers ≤ total for every response. Comparison: baseline = the clean run, 12 REGRESSED = the observed outcome changes. UI: Failed filter (13 rows), assertion row `$.items[0].price` number vs `string "19.90"`, body tree marks `widthCm: -1` with its finding, timing breakdown, comparison page; run, result and comparison pages without horizontal scrolling at 390 px; no JavaScript errors |
| Secrets after the completion runs | `psql` and the backend log | ✅ The demo key appears **0 times** in results (requests, responses, headers, errors, **assertions**) and in the backend log; all 512 results of the runs recorded the time to headers |

### Defects found and fixed during this phase

- **Unexpected successes were not body-validated.** Phase 4 turned schema validation off for tests whose *expected* statuses document no body, so the sample's quantity deviation (201 with `quantity: 1000`) reported only the status. Contract validation now applies to whatever status comes back (generator version 2); the run now also reports `$.lines[0].quantity` `maximum`.
- **The specs documented fewer statuses than the servers send.** The integration-test target returns 400 for invalid ids on DELETE and the Warehouse sample returns 401 on every protected operation and 415 on PATCH and `POST /orders`; contract validation flagged them as undocumented. The specs now document them (the 401/403 fallback test uses its own fixture).
- "at least 1 characters" in findings: counts are now pluralized.
- The comparison page overflowed by 72 px at phone width (native select sized to its longest option); it is full-width on small screens now.
- Edits written by Python on Windows had turned some working-tree files into CRLF (the index stayed LF); a fixture-replacement test caught it, and the files were normalized.

### Known limitations

- Only JSON bodies are validated against schemas; XML and other media types with a schema are reported INCONCLUSIVE.
- Bodies over the stored or read limit are INCONCLUSIVE (not validated partially).
- Formats other than the 13 listed are annotations; `if`/`then`/`else`, `prefixItems`, `dependentSchemas`, `unevaluatedProperties` and similar 3.1 keywords make the body INCONCLUSIVE.
- Response headers are not validated against documented header schemas yet.
- Responses are validated against the specification that is **active when the test runs**, not the version a test was generated from.
- No aggregated per-rule report; findings are per result, and comparison is per test.
- Run progress is polled (1 s), not pushed. GitHub CI status for this branch is checked by the user.

## History

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
- [ ] Coverage and dashboard
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
| Test history and coverage persist | ◐ Runs, results and assertions persist, with comparison (Phases 5–6); coverage pending |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ✅ incl. 168 outbound security tests and execution ITs |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
