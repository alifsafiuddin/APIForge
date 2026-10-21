# Project Status

**Current phase**: Phase 3 — OpenAPI specification import and explorer — **complete** (2026-10-21)
**Next phase**: decided by the next phase brief (planned: environments, outbound request safety, ad hoc execution and contract validation)
**Runnable today**: registration and sign-in, private projects, OpenAPI 3.0/3.1 import (upload or paste) with immutable versions, the specification explorer, read-only execution history API. Not yet: environments, test generation and execution, contract validation, coverage, AI.

## Phase 3 summary

### Delivered

| Requirement | Implementation | Where |
|---|---|---|
| OpenAPI 3.x JSON and YAML | Format detected from content; 3.0.x and 3.1.x accepted, Swagger 2.0 and other versions rejected with guidance | `spec/internal/DocumentReader`, `SpecificationAnalyzer` |
| File upload and text input | `POST /specs` as multipart (`file`) or JSON (`{content}`); same for `/specs/validate` (dry run) | `spec/web/SpecificationController` |
| Size and content limits | 5 MB document limit (multipart limits, `RequestSizeLimitFilter` for every body incl. chunked, service check); strict UTF-8; YAML alias, nesting, code-point limits; no duplicate keys; ≤ 2,000 operations; path length | `config/RequestSizeLimitFilter`, `spec/internal/*` |
| Maintained parser | swagger-parser 2.1.48 (OpenAPI 3 module), fed a pre-validated JSON string | `spec/internal/OpenApiParserAdapter` |
| Understandable errors | Every finding is `{severity, code, message, location}`; line/column for syntax errors, document path otherwise; 422 `SPEC_INVALID` lists them all | `ImportMessage`, API_DESIGN §2.3 |
| Metadata extraction | Info, servers, tags, security schemes, component schemas; per operation: method, path, operationId, summary, description, tags, deprecated, effective parameters, request body, ordered responses with headers, effective security | `spec/internal/OpenApiNormalizer` |
| Local references and nested objects | Parameter/request-body/response/header references resolved; schema references kept and resolved against stored component schemas (recursion-safe) | `OpenApiNormalizer`, `SchemaView` |
| Duplicate/ambiguous operations, unsupported features | Duplicate keys (hidden duplicate paths/methods), duplicate operationIds, ambiguous paths, undeclared path parameters → errors; callbacks, webhooks, links, cookie parameters, non-JSON-only bodies, OAuth2/OIDC/mTLS, 3.1 validation scope → warnings/notes | `SpecificationAnalyzer` |
| Persist original, version, normalized metadata | V7 migration: `api_operations` (immutable) + new `specification_versions` columns; raw document stored byte for byte with SHA-256 | `db/migration/V7__…` |
| Versions keep their test results | Versions and operations are immutable; runs keep `specification_version_id`; activating another version marks tests for vanished operations STALE (kept) and revives returning ones | `SpecificationService.activate`, ITs |
| No outbound HTTP during import | Every non-local `$ref` is rejected before parsing; the parser gets content, never a location | `ReferenceGuard`; IT with a live local server receiving 0 requests |
| Explorer | Project page → Specification: version selector, searchable endpoint index grouped by tag with method badges and filters, version overview (servers, schemes, tags, findings, download original), endpoint detail (parameters, request body, responses, headers, security, warnings) with an expandable schema viewer; import dialog (upload/drag-and-drop or paste, dry-run check) | `frontend/src/features/specs/*` |

### Verification (executed 2026-10-21; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Method | Result |
|---|---|---|
| Backend build and tests | `./mvnw verify` | ✅ **189 tests, 0 failures, 0 errors, 0 skipped**; line coverage 92.2% (1657/1798), branch 76.6% |
| Frontend lint / typecheck / tests | `npm run check` | ✅ 0 lint findings (no suppressions), tsc clean, **74/74 tests**; coverage lines 92.0%, branches 77.6% |
| Frontend build / audit | `npm run build`, `npm audit` | ✅ no warnings; 0 vulnerabilities |
| Required test cases | Valid JSON; valid YAML; malformed input; missing required fields; nested references; arrays, enums, nullable, required; unsupported/invalid references; oversized uploads | ✅ all covered (TESTING.md §7: `SpecificationAnalyzerTest`, `SpecificationImportIntegrationTest`, `RequestSizeLimitFilterTest`, `SchemaView.test.tsx`, `SpecificationPage.test.tsx`) |
| **Completion check**: import a real sample, verify the endpoint inventory, refresh, confirm persistence | Headless Microsoft Edge against the running stack (PostgreSQL in Docker, backend, Vite): registered, created a project, **uploaded `samples/petstore3-openapi.yaml` through the browser** (real multipart), dry-run check, import; compared the inventory with the document; opened `GET /pet/{petId}`; reloaded the page; searched; tried an invalid import; mobile layout | ✅ 15/15 checks: version 1 with **19 endpoints, identical to the document's 19 (method, path) pairs in order**; detail showed the required `petId` path parameter, responses 200/400/404/default and the expanded `Pet` schema with its status enum; **after reload the endpoint detail and all 19 endpoints were still there**; search narrowed to 3 order endpoints; the invalid document showed "Required field 'info' is missing." with its location; no JavaScript errors |
| Persistence in the database | `psql` on the dev database after the browser run | ✅ `specification_versions`: v1, active, 19 operations, YAML, 23,165 bytes (= the file); 19 `api_operations` rows; `SPECIFICATION_IMPORTED` audit event |
| Migration on existing data | Backend start against the Phase 2 dev database | ✅ V6 → V7 applied |
| Clean checkout | `git checkout-index` of the staged tree into an empty directory; `mvnw verify`, `npm ci && npm run check && npm run build` | See final line below |

Clean checkout (final staged tree): ✅ backend 189/189 tests, BUILD SUCCESS; `npm ci` (0 vulnerabilities), `npm run check` 74/74, `npm run build` with 0 warnings.

### Defects found and fixed during this phase

- **YAML aliases were silently corrupted**: Jackson's YAML parser returns `*alias` as a plain string, so documents using anchors imported wrong data and the alias limit never applied. Found by the alias-bomb test; YAML is now loaded with SnakeYAML under explicit limits (ADR-024).
- **Undeclared path parameters were accepted** (swagger-parser does not report them); now an error.
- **Array parameters' `$ref`s were skipped** by the first reference-guard draft (array items were treated as author-chosen names); fixed before the first run.
- **Module cycle** `common` ↔ `config` introduced by the size filter; caught by the ArchUnit cycle rule, filter moved to `config`.
- **Silent UI failure**: the import dialog only displayed API errors, so a network failure showed nothing; all failures are now shown (with a test).
- **Mobile overflow**: a long version title widened the version selector beyond a phone screen; found by the browser check, fixed.
- **Schema viewer collapsed one-level references** (e.g. an enum behind `$ref`) because expansion counted property depth; now counts reference depth.

### Known limitations

- Specifications split across files must be bundled before import (by design: no fetching).
- OpenAPI 3.1-specific schema keywords are displayed as written; contract validation support for them is decided with the validator (ADR-021).
- Markdown in descriptions is shown as plain text (safe by design; no rich rendering).
- Sessions and rate limits are in memory (single instance); GitHub CI has not run yet (no remote).
- The frontend upload component test asserts the multipart request at the `fetch` boundary because of a Vitest/jsdom FormData limitation; the real upload is verified by backend ITs and the browser check (ADR-024).

## History

- **Phase 2 (2026-10-21)** — Database schema (V2–V6), session authentication with CSRF, lockout, rate limiting and audit trail; ownership-based authorization with 404 for foreign resources; projects CRUD; read-only execution history; ArchUnit rules. 129 backend and 51 frontend tests; committed as `0da6635` (branch `phase-2-database-auth`).
- **Phase 1 (2026-10-21)** — Runnable foundation: Spring Boot 4.1.1, PostgreSQL 18.6, Flyway, Actuator health, problem+json errors, request correlation, CORS, React shell, CI workflow. Committed as `fbbd96f`.
- **Phase 0 (2026-10-20)** — Design documents and verified dependency matrix.

## Open risks carried forward

1. **openapi-request-validator message mapping** (JSON path, expected/actual) and **regex DoS in schema `pattern`**: spike when contract validation starts (ADR-006).
2. **Small-model JSON reliability** for AI features (ADR-019).

## Milestone checklist

- [x] Phase 0 — Design
- [x] Phase 1 — Foundation
- [x] Phase 2 — Database design and authentication
- [x] Phase 3 — OpenAPI import and explorer
- [ ] Environments (AES-GCM secrets, redaction), outbound safety (`outbound` module, `demo-target`), ad hoc execution, contract validation
- [ ] Deterministic test generation and runs
- [ ] Coverage and dashboard
- [ ] AI (Ollama via Spring AI)
- [ ] Hardening and release (Dockerfiles, nginx, full Compose, Playwright E2E in CI)

## Completion definition tracker

| Criterion | Status |
|---|---|
| Frontend and backend run together | ✅ (dev mode) |
| PostgreSQL persists real application data | ✅ users, projects, specifications, operations, audit trail |
| Valid OpenAPI spec can be imported and explored | ✅ Phase 3 (verified with the official Petstore 3 document) |
| Real tests generated and executed against an authorized test API | ☐ |
| Contract validation produces explainable results | ☐ |
| Test history and coverage persist | ◐ History schema and read API exist; nothing writes runs yet |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ◐ Auth, authorization and import-time no-fetch ✅; execution-time outbound safeguards pending |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
