# Project Status

**Current phase**: Phase 2 — database design and authentication — **complete** (2026-10-21)
**Next phase**: Phase 3 — OpenAPI specification import and explorer (tentative; the next phase brief decides)
**Runnable today**: registration and sign-in, private project management, read-only execution history API, complete persistence model. Not yet: specification import, test generation and execution, contract validation, coverage, AI.

## Phase 2 summary

### Delivered

| Area | What exists | Where |
|---|---|---|
| Schema | Migrations V2–V6: `users`, `audit_events`, `api_projects`, `specification_versions`, `environments`, `environment_variables`, `test_suites`, `test_cases`, `test_runs`, `test_results`. UUIDv7 ids, explicit FKs with deliberate delete rules, indexes, timestamps, check constraints, composite same-project FKs, append-only triggers | `db/migration/`, [DATABASE_SCHEMA.md](DATABASE_SCHEMA.md) |
| Entities | JPA entities and repositories for every table (`ddl-auto=validate` checks them against the schema on every start) | `*/domain`, `*/repository` |
| Authentication | Registration, JSON login, logout, `/auth/me`; bcrypt (cost 12) via `DelegatingPasswordEncoder`; NIST-style password policy; server-side sessions (30 min idle, 12 h absolute); session-id and CSRF-token rotation on login; SPA CSRF protection; uniform failure responses; account lockout; per-IP rate limits; registration switch | `auth/`, `config/SecurityConfig` |
| Authorization | API level (authenticated by default, explicit public allow-list); service level (`ProjectAccessService.requireOwned`, parent-scoped child lookups); 404 for foreign resources; database-level same-project FKs; ArchUnit rules | `project/service`, `execution/service`, `ArchitectureTest` |
| Audit | Security event trail (registration, sign-ins, failures, lockouts, logout, project create/delete, cross-user attempts) with IP and request id; append-only | `audit/` |
| APIs | Auth endpoints; projects CRUD with search, sort allow-list, pagination (capped at 100), optimistic locking; read-only runs/results history | [API_DESIGN.md](API_DESIGN.md) |
| Errors | Centralized handling extended: `ApiException` codes, optimistic lock → `STALE_VERSION`, integrity → `CONFLICT` (no constraint names leaked), security exceptions → 401/403 problem+json from filters and controllers | `common/error/` |
| Frontend | Login and registration pages (client validation mirroring server rules, server field errors), route guard with safe `next` redirect, global 401 handling, user menu with sign-out, Projects page (search, sort, active/archived, paging, create/edit/archive/delete with typed confirmation), recent projects on the Overview, CSRF-aware API client with one automatic retry on token rotation | `frontend/src/features/{auth,projects}` |

**Deliberately not built in Phase 2**: `coverage_snapshots` (optional in the brief; coverage is derived, ADR-013). Environment, test-suite and test-case APIs (their tables and entities exist; the endpoints arrive with the phases that add their safety controls: SSRF policy and secret encryption, review gate). Account settings (password change, display name).

### Verification (all executed on 2026-10-21; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Method | Result |
|---|---|---|
| Backend build and tests | `./mvnw verify` (warnings are errors, enforcer, JaCoCo) | ✅ **129 tests, 0 failures, 0 errors, 0 skipped**. Line coverage 90.5% (900/995), branch 76.6% (190/248) |
| Frontend lint / typecheck / tests | `npm run check` | ✅ 0 lint findings (no suppressions), tsc clean, **51/51 tests** |
| Frontend coverage | `vitest --coverage` | Lines 89.8%, branches 81.2%, functions 84.7% |
| Frontend build | `npm run build` | ✅ no warnings; largest chunk 221 kB |
| Dependency audit | `npm audit` | ✅ 0 vulnerabilities (after removing the shadcn CLI dependency, ADR-023) |
| Registration | ITs + headless Edge against the real stack | ✅ account created, signed in, only `{bcrypt}$2a$12…` stored (checked in the dev DB), no plaintext anywhere |
| Login / invalid credentials | ITs + browser | ✅ success starts a rotated session; unknown e-mail and wrong password give identical 401s; lockout after 5 failures |
| Unauthorized access | ITs | ✅ every protected endpoint and unknown path → 401 problem+json; no CSRF token → 403 with no side effect |
| Cross-user access | `CrossUserAccessIntegrationTest` + browser (second user probing the first user's project id) | ✅ 404 everywhere, indistinguishable from a missing project, owner data unchanged, attempt audited (`CROSS_USER_ACCESS_DENIED` row in the dev DB) |
| Validation failures | ITs (backend) + component tests (frontend) | ✅ per-field `errors[]`, unknown properties rejected, sort allow-list, UI shows server field errors next to fields |
| Persistence | `SchemaIntegrationTest`, `ProjectIntegrationTest`, browser sign-out/sign-in | ✅ round trips incl. jsonb; cascades and SET NULL rules as documented; data survives sign-out and backend restart |
| Database migrations | Fresh DB (Testcontainers) and incremental upgrade of the Phase 1 dev DB (V1 → V6) | ✅ both; `flyway_schema_history` shows V1–V6 successful |
| Real app security config | Backend log in `dev` | ✅ no generated default password (custom user store in use) |
| Clean checkout | `git checkout-index` of the staged tree into an empty directory; `mvnw verify`, `npm ci && npm run check && npm run build` | ✅ see final line of this section |

Clean checkout (final staged tree): ✅ backend 129/129 tests, BUILD SUCCESS; `npm ci` (0 vulnerabilities), `npm run check` 51/51, `npm run build` with 0 warnings.

### Defects found and fixed during this phase

- **Self-deadlock in audit writes** (design review, before any run): audit events written in a new transaction would wait on the uncommitted user row during registration. Audits now join the caller's transaction; only rollback-surviving events use their own (ADR-023).
- **Ownership check ordering**: renaming a project then checking for duplicates triggered an auto-flush into the unique index (generic 409 instead of `DUPLICATE_NAME`). The target state is now validated before mutating.
- **Test fixture violated `ck_test_runs_timeline`**: the constraint correctly caught a run "started" before it was created; the fixture now persists first.
- **Module cycle** (`audit` ↔ `auth`) found by the new ArchUnit rule; the logout listener moved to `auth`.
- **Supply-chain advisory** in the shadcn CLI tree (unpatched `braces`); the CLI is no longer a dependency.

### Known limitations

- Sessions and rate-limit counters are in memory: single backend instance, and a restart signs users out (ADR-008/014).
- GitHub CI has not run yet (no remote configured).
- Maven still prints two `HTTP 401` warnings for repositories declared in Flyway's own POM (third-party, harmless).

## Phase 1 summary (2026-10-21)

Runnable foundation: Spring Boot 4.1.1 backend, PostgreSQL 18.6 via Compose, Flyway, Actuator health (accurate DOWN/503 when the DB stops), problem+json errors, request correlation, CORS, React shell with live status, CI workflow. 44 backend and 13 frontend tests at the time; verified from a clean checkout. Committed as `fbbd96f`.

## Phase 0 summary (2026-10-20)

Design documents and verified dependency matrix; no code.

## Open risks carried forward

1. **openapi-request-validator message mapping** (JSON path, expected/actual): spike when contract validation starts, networknt fallback (ADR-006).
2. **Regex DoS in schema `pattern`**: mitigation depends on validator hooks (same spike).
3. **Small-model JSON reliability**: AI phase model evaluation (ADR-019).

## Implementation plan and milestone checklist

Phase numbers after 2 are tentative and follow the next phase briefs.

### Phase 1 — Foundation ✅
### Phase 2 — Database design and authentication ✅
- [x] Entities and Flyway migrations for all core tables, with FKs, indexes, timestamps, delete rules
- [x] Registration, login, logout, session expiry (idle + absolute), CSRF, lockout, rate limiting
- [x] Authorization at API and service levels; cross-user isolation; audit trail
- [x] Bean Validation, DTOs only, pagination, centralized exception handling, transactions
- [x] Unit, slice, integration, architecture and frontend tests
- [x] Frontend: login, registration, route protection, user menu, projects management

### Phase 3 — Specification import and explorer
- [ ] Ref guard (local `$ref` only), parser adapter (Jackson 2 isolated), size/YAML limits
- [ ] Immutable versions, `api_operations` projection (new migration), activation, stale marking
- [ ] Validate (dry-run) endpoint with readable errors
- [ ] UI: project page, spec explorer, endpoint detail, import dialog

### Phase 4 — Environments, outbound safety, ad hoc execution, contract validation
- [ ] Environment/variable APIs with AES-GCM secrets and redaction
- [ ] `outbound` module (SSRF guard, guarded DNS, redirects, limits) and `demo-target`
- [ ] Ad hoc execution writing `test_runs`/`test_results`; validator spike and `ContractValidator`

### Phase 5 — Deterministic test generation and runs
### Phase 6 — Coverage and dashboard
### Phase 7 — AI (Ollama via Spring AI)
### Phase 8 — Hardening and release (Dockerfiles, nginx, full Compose, Playwright E2E in CI)

## Completion definition tracker

| Criterion | Status |
|---|---|
| Frontend and backend run together | ✅ (dev mode) |
| PostgreSQL persists real application data | ✅ users, projects, audit trail (Phase 2) |
| Valid OpenAPI spec can be imported and explored | ☐ |
| Real tests generated and executed against an authorized test API | ☐ |
| Contract validation produces explainable results | ☐ |
| Test history and coverage persist | ◐ History schema and read API exist; nothing writes runs yet |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ◐ Auth and authorization ✅; outbound safeguards pending |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 2 scope |
| No unresolved critical defects or falsely advertised features | ✅ README and UI describe only what exists |
