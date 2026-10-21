# Project Status

**Current phase**: Phase 4 — Deterministic test-case generation — **complete** (2026-10-21)
**Next phase**: decided by the next phase brief (planned: environments, outbound request safety, execution and contract validation)
**Runnable today**: registration and sign-in, private projects, OpenAPI 3.0/3.1 import with immutable versions, the specification explorer, **deterministic test generation with a review and curation workspace**, read-only execution history API. Not yet: environments, test execution, contract validation, coverage, AI.

## Phase 4 summary

### Delivered

| Requirement | Implementation | Where |
|---|---|---|
| Deterministic engine before AI | Pure generator over the stored, normalized operations and component schemas; no randomness except RgxGen seeded by position; generator version stored with each test | `generation/engine/*` (ADR-025) |
| Inspect operations, parameters, request/response schemas, constraints, security | Effective parameters by location, JSON request bodies (incl. `+json`, `*/*`), `$ref`/`allOf`/`oneOf`/discriminator, 3.0 and 3.1 constraints, documented responses and their schemas, effective security requirements and schemes | `SchemaResolver`, `Schema`, `ResponseCatalog`, `DeterministicTestGenerator` |
| Scenarios from the brief | Valid request (minimal and complete); missing required fields; wrong primitive types; invalid enum values; below/above/at minimum and maximum (inclusive and exclusive); string length below/above/at; array length below/above/at; missing required query and header parameters (path: not representable, reported); unsupported content type; documented success and error statuses with response-schema checks; requests without and with invalid credentials | Category per scenario: `HAPPY_PATH`, `MISSING_FIELD`, `INVALID_TYPE`, `ENUM_VALUE`, `BOUNDARY_VALUE`, `STRING_LENGTH`, `ARRAY_LENGTH`, `QUERY_PARAMETER`, `HEADER`, `CONTENT_TYPE`, `AUTH` |
| No empty-body assumption; no impossible or misleading tests | Body scenarios only where a body is documented; optional bodies omitted from the minimal request; required non-JSON bodies only get "missing body". Every valid value is validated; every negative breaks exactly one constraint (single-fault rule) or is skipped with the reason; unevaluable patterns make values impossible rather than unverified | `SchemaValidator`, `ValueSynthesizer` |
| Required properties and parameter locations | Missing-field tests only for `required`; readOnly never sent; path parameters always set; string parameters never get type tests; cookie and reserved headers reported | `DeterministicTestGenerator` |
| Explicit expected outcomes | Documented 2XX for success; documented 400/422 (path 400/404/422, content type 415, missing body 400/415/422) else 4XX, stated; 401/403 for authentication; documented response bodies listed for schema validation | `ResponseCatalog`, `Expectations` |
| Reason each test was generated | `rationale` (sentence), `spec_location` (pointer into the spec), `scenario_key`; skipped scenarios returned with reasons by generation and by the per-operation plan | V8 columns, `GET …/tests/generation-plan` |
| Deterministic vs AI-suggested | `origin` (`DETERMINISTIC`, `AI_SUGGESTED`, `MANUAL`) shown in list and detail; AI suggestions need review (existing rule) and return to review when edited | `TestCase` |
| Stable identifiers, no uncontrolled duplicates | `dedupe_hash = SHA-256(operation key, scenario key)` under the partial unique index; regeneration updates in place, keeps user edits, archives obsolete generated tests | `TestGenerationService` |
| Review, edit, enable, disable, organize | Test workspace (Tests tab): endpoint index with counts, filters, bulk enable/disable/move, detail panel (why, expected, request, provenance), edit with validation, approve/reject, suites dialog; REST API in API_DESIGN §2.7 | `testcase/*`, `frontend/src/features/tests/*` |
| Persist suites and cases in PostgreSQL | `test_suites`, `test_cases` (V5) extended by V8; one GENERATED suite per project; audit `TESTS_GENERATED` | `V8__deterministic_test_generation.sql` |
| Comprehensive unit tests for schema edge cases | 57 validator + 53 synthesizer + 35 generator scenario tests | TESTING.md §7 |

### Verification (executed 2026-10-21; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Method | Result |
|---|---|---|
| Backend build and tests (clean checkout) | `git checkout-index` of the staged tree into an empty directory, `mvnw verify` | ✅ **388 tests, 0 failures, 0 errors, 0 skipped**; enforcer rules pass; line coverage 92.6% (3531/3812), branch 78.1%; `generation` package 91.0% lines / 78.5% branches (plan target ≥ 90% lines) |
| Frontend (clean checkout) | `npm ci`, `npm run check`, `npm run build` | ✅ 0 vulnerabilities; 0 lint findings; tsc clean; **81/81 tests**; build without warnings (final staged tree, re-exported after the last test was added); coverage lines 89.1%, branches 74.7% |
| Migration on existing data | Backend start against the Phase 3 dev database | ✅ V7 → V8 applied |
| **Completion check**: a sample with multiple data types, constraints and response schemas; generated tests are valid, explainable and repeatable | Headless Microsoft Edge against the running stack (PostgreSQL in Docker, backend jar, Vite). Registered, created a project, uploaded **`samples/warehouse-lab-openapi.yaml`** (7 endpoints), generated, inspected, edited one test, disabled another, reloaded, regenerated, checked the mobile layout; then queried the database | ✅ **19/19 checks.** **128 tests** across 10 categories, every one with an explicit expected status; report lists skipped scenarios with reasons (e.g. oneOf field, missing path parameter). **Valid**: the generator test validates every generated request of five fixture specs (incl. this one) against its claim. **Explainable**: the detail of "Missing required field 'name'" shows *"'name' is listed in 'required' of schema NewProduct. The request omits it."*, expected 400/422 with the `Error` schema checks, source `#/components/schemas/NewProduct/required`. **Repeatable**: regeneration reported *0 new, 0 updated, 127 unchanged, 1 edited by you*; all 128 test ids identical before and after; the edit and the disabled state survived reload and regeneration; no JavaScript errors; no horizontal scroll at 390 px |
| Persistence in the database | `psql` after the browser run | ✅ 128 rows, 128 distinct identities, exactly 1 user-edited and 1 disabled; no row without rationale, location, scenario key or generator version; `TESTS_GENERATED` audit events with created 128 then 0 |

### Defects found and fixed during this phase

- **Required array query parameters were sent empty** in the minimal request (`tags=[]` is indistinguishable from omitting it); parameters now always use a non-empty value, and a parameter that can only be empty is reported.
- **The array-size helper validated against the array's own maxItems**, so "more than maxItems" fell back to duplicate items and was wrongly skipped as a uniqueness conflict; the helper now builds raw item lists and the skip reason names the *other* violated constraint.
- **An unevaluable `pattern` let the synthesizer accept "sample"** as valid; such values are now impossible rather than unverified.
- **The backtracking test used `(a+)+`, which Java memoizes**; replaced by a pattern that really exceeds the read budget (`^(.*a){12}$`, abandoned after ~6 ms).
- **HQL could not instantiate a nested record** (`TestCaseRepository.OperationCount`); moved to a top-level projection record (caught by the Spring context in integration tests).
- **The test panel kept the component's narrow default width** (`max-w-sm` beats `sm:max-w-2xl`); widened with the matching variant.
- Text: article ("an array"), clearer type rationales ("Field 'id' must be integer (…)"), array-root labels (`body[0].email`).

### Known limitations

- Tests cannot be executed yet (no environments/runs); `validateResponseSchema` and response checks are recorded for the executor.
- Pattern-violation tests are not generated (ADR-025); fields inside oneOf/anyOf are not mutated; non-JSON bodies only get "missing body"; at most 100 tests per operation.
- Tests are created by generation only (no blank manual test); disable a test to stop it from running.
- The browser check found the backend process ending once with `0xC0000005` while started through `mvnw spring-boot:run` from a background PowerShell job, without a JVM crash report; the same build ran the complete check as `java -jar` without problems. Recorded, not reproduced.
- Sessions and rate limits are in memory (single instance); GitHub CI status for this branch is checked by the user.

## History

- **Phase 3 (2026-10-21)** — OpenAPI 3.0/3.1 import (upload or paste) with strict validation, local-only references, immutable versions and normalized operations (V7); specification explorer. 189 backend and 74 frontend tests; completion check with the official Petstore 3 sample. Committed as `396be8e`.
- **Phase 2 (2026-10-21)** — Database schema (V2–V6), session authentication with CSRF, lockout, rate limiting and audit trail; ownership-based authorization with 404 for foreign resources; projects CRUD; read-only execution history; ArchUnit rules. 129 backend and 51 frontend tests; committed as `0da6635` (branch `phase-2-database-auth`).
- **Phase 1 (2026-10-21)** — Runnable foundation: Spring Boot 4.1.1, PostgreSQL 18.6, Flyway, Actuator health, problem+json errors, request correlation, CORS, React shell, CI workflow. Committed as `fbbd96f`.
- **Phase 0 (2026-10-20)** — Design documents and verified dependency matrix.

## Open risks carried forward

1. **openapi-request-validator message mapping** (JSON path, expected/actual) and **regex DoS in schema `pattern`** inside that library: spike when contract validation starts (ADR-006). The generator's own pattern matching is already budgeted (ADR-025).
2. **Small-model JSON reliability** for AI features (ADR-019).

## Milestone checklist

- [x] Phase 0 — Design
- [x] Phase 1 — Foundation
- [x] Phase 2 — Database design and authentication
- [x] Phase 3 — OpenAPI import and explorer
- [x] Phase 4 — Deterministic test generation
- [ ] Environments (AES-GCM secrets, redaction), outbound safety (`outbound` module, `demo-target`), ad hoc execution, contract validation
- [ ] Test runs (execution)
- [ ] Coverage and dashboard
- [ ] AI (Ollama via Spring AI)
- [ ] Hardening and release (Dockerfiles, nginx, full Compose, Playwright E2E in CI)

## Completion definition tracker

| Criterion | Status |
|---|---|
| Frontend and backend run together | ✅ (dev mode) |
| PostgreSQL persists real application data | ✅ users, projects, specifications, operations, test suites and cases, audit trail |
| Valid OpenAPI spec can be imported and explored | ✅ Phase 3 (verified with the official Petstore 3 document) |
| Real tests generated and executed against an authorized test API | ◐ Generated (Phase 4); execution pending |
| Contract validation produces explainable results | ☐ |
| Test history and coverage persist | ◐ History schema and read API exist; nothing writes runs yet |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ◐ Auth, authorization and import-time no-fetch ✅; execution-time outbound safeguards pending |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
