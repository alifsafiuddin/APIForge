# Project Status

**Current phase**: Phase 1 — runnable project foundation — **complete** (2026-10-21)
**Next phase**: Phase 2 — authentication, projects, and OpenAPI import/explorer
**Runnable today**: backend (Spring Boot 4.1.1) + PostgreSQL 18.6 (Docker Compose) + frontend shell (React 19 / Vite 8). No API-testing features exist yet.

## Phase 1 summary

### Delivered

| Area | What exists | Where |
|---|---|---|
| Backend scaffold | Spring Boot 4.1.1, Java 21, Maven Wrapper (Maven 3.10.0), packages `common`, `config`, `system` | `backend/` |
| Build quality | `-Xlint:all` with warnings as errors (except `processing`, ADR-022), enforcer (Java 21+, Maven 3.9+, dependency convergence, no duplicate deps), JaCoCo report, Mockito loaded as an agent | `backend/pom.xml` |
| Database | PostgreSQL 18.6 via Compose (loopback-only port, healthcheck, named volume); Flyway `V1__enable_extensions.sql` (`citext`); Hibernate `ddl-auto=validate` | `docker-compose.yml`, `db/migration/` |
| Configuration | `application.yml` (env-driven, production-safe defaults), `dev` profile (reads root `.env`, CORS for :5173, DEBUG logging), `prod` profile (required DB vars without defaults, ECS JSON logs); validated `apiforge.*` properties | `backend/src/main/resources/` |
| Health | Actuator `health` (components visible, details hidden), `liveness` (process only), `readiness` (+db); only `health` and `info` exposed | `application.yml` |
| Errors | RFC 9457 problem+json with `code`, `requestId`, `errors[]` for MVC exceptions **and** container-level errors | `common/error/` |
| Correlation & logging | `X-Request-Id` filter (safe reuse or UUID), MDC `requestId` on every log line, one access-log line per request without query, headers or bodies | `common/web/RequestCorrelationFilter.java` |
| CORS | `/api/**` only, explicit origins (wildcards fail startup), credentials allowed, `X-Request-Id` exposed | `config/WebConfig.java` |
| API | `GET /api/v1/system/info` | `system/` |
| Frontend | Vite 8 + React 19 + TS 6.0 (strict, `noUncheckedIndexedAccess`) + Tailwind 4 + shadcn/ui (radix-nova) + React Router 8 + TanStack Query + zod; responsive shell (sidebar, mobile drawer, skip link), Overview with **live** system status, 404 and error pages; typed API client that validates every response and parses problem+json | `frontend/src/` |
| Dev scripts | Backend `./mvnw spring-boot:run` / `verify`; frontend `dev`, `build`, `preview`, `lint`, `typecheck`, `test`, `test:coverage`, `check`; Vite proxy for `/api` and `/actuator` | `package.json`, `vite.config.ts` |
| Repo hygiene | `.gitignore` (secrets, build output, deps, local env), `.gitattributes` (LF; CRLF for `.cmd`), `.editorconfig`, `.env.example` files with placeholders only | root, `frontend/` |
| CI | GitHub Actions (pinned SHAs, read-only token): backend verify with Testcontainers, frontend lint/typecheck/test/build/audit, Compose smoke test. Dependabot for Maven, npm, Actions, Compose | `.github/` |

**Not done in Phase 1 (deliberately)**: authentication and Spring Security move to Phase 2 (ADR-022). ArchUnit rules start in Phase 2, when there are layered modules to check. Backend and frontend Dockerfiles arrive in Phase 8.

### Verification (all commands actually executed on 2026-10-20/21, Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Command / method | Result |
|---|---|---|
| Backend compiles | `./mvnw verify` (warnings are errors) | ✅ BUILD SUCCESS |
| Backend tests | same | ✅ **44 run, 0 failures, 0 errors, 0 skipped**. Line coverage 96.4% (161/167), branch 81.5% (44/54) |
| Frontend lint / typecheck / tests | `npm run check` | ✅ 0 lint findings; tsc clean; **13/13 tests passed** |
| Frontend coverage | `npm run test:coverage` | Statements/lines 87.3%, branches 75.5%, functions 83.0% |
| Frontend builds | `npm run build` | ✅ No warnings; largest chunk 219 kB (react) |
| PostgreSQL starts | `docker compose up -d --wait postgres` | ✅ healthy, bound to `127.0.0.1:5432` |
| Flyway migrations execute | backend startup log against Compose DB | ✅ "Successfully applied 1 migration … now at version v1"; second start: "Schema is up to date" (data persisted) |
| Health checks are accurate | `curl` with DB up, then `docker compose stop postgres` | ✅ UP/200 with DB up; with DB stopped: `/health` and `/readiness` **DOWN/503** (db DOWN), `/liveness` UP/200; back to UP after restart |
| Frontend calls backend | `curl` through the Vite proxy, and headless Microsoft Edge (Playwright, desktop 1440×900 and mobile 390×844) | ✅ `/api/v1/system/info` and `/actuator/health` 200 via proxy; page renders live status; no console errors; no horizontal scroll; mobile drawer opens and closes on navigation; unknown route shows 404 |
| CORS | `curl` preflight | ✅ `http://localhost:5173` allowed with credentials; `https://evil.example` 403 |
| No secrets committed | staged-file review + `git grep` for the real local password, key patterns, `password=` literals | ✅ `.env` ignored; only placeholders and the CI-only throwaway password `ci-only-password` |
| Clean checkout | `git checkout-index` of the staged tree into an empty directory, then backend `mvnw verify`, frontend `npm ci && npm run check && npm run build`, `docker compose config` | ✅ Final staged tree: backend 44/44 tests, BUILD SUCCESS; `npm ci`, `check` (13/13) and `build` (0 warnings) passed; `docker compose config` valid. Without `APIFORGE_DB_PASSWORD`, Compose fails with a clear message, as intended |

### Known limitations and notes

- **CI has not run on GitHub yet**: the repository has no remote. The workflow was written to mirror the commands verified locally.
- Maven prints two `HTTP 401` warnings while resolving prefix metadata for repositories declared in Flyway's own POM (Redgate feeds). Resolution is unaffected, and these come from a third-party POM, not our configuration.
- Hibernate starts with no entities yet; `ddl-auto=validate` becomes meaningful from Phase 2.
- The local editor session needed a restart after the Docker Desktop and Node 24 installs. The user `PATH` still contains Laragon's Node 18 after `D:\nodejs` (see the README troubleshooting section).

## Phase 0 summary (2026-10-20)

Design documents written; dependency compatibility verified ([DECISIONS.md](DECISIONS.md)); no code. Review outcomes are preserved in the ADRs.

## Open risks carried forward

1. **openapi-request-validator message mapping** (JSON path, expected and actual values): spike in Phase 4, networknt fallback (ADR-006).
2. **Regex DoS in schema `pattern`**: mitigation depends on validator hooks (Phase 4 spike).
3. **Small-model JSON reliability**: Phase 7 model evaluation (ADR-019).

## Implementation plan and milestone checklist

### Phase 1 — Foundation ✅
- [x] `git init`, `.gitignore`, `.gitattributes`, `.editorconfig`, `.env.example`
- [x] Backend scaffold (Boot 4.1.1, Java 21, Maven Wrapper), config properties, profiles
- [x] Enforcer (Java/Maven versions, dependency convergence), `-Xlint:all` + failOnWarning, JaCoCo
- [x] Flyway `V1`; JPA `ddl-auto=validate`
- [x] Problem+json error handling (MVC + container), request-id filter, structured logging config
- [x] Actuator health/info (liveness/readiness)
- [x] CORS for the local frontend origin
- [x] Frontend scaffold, app shell (sidebar, responsive drawer), API client, live system status
- [x] Frontend tests (Vitest + MSW), oxlint, typecheck
- [x] `docker-compose.yml` with PostgreSQL
- [x] GitHub Actions (backend, frontend, compose) + Dependabot
- [x] README build and run instructions

### Phase 2 — Authentication, projects, and specification import/explorer
- [ ] Spring Security: session auth, CSRF (SPA), register/login/logout/me, lockout + rate limit, password change; `V2__users.sql`
- [ ] Auth tests: unit + ITs (CSRF, lockout, session fixation); frontend login/register pages, protected routes, user menu
- [ ] ArchUnit layering rules
- [ ] Project CRUD with ownership; cross-user access IT matrix
- [ ] Ref guard, parser adapter (Jackson 2 isolated), YAML/size limits
- [ ] Spec versions (immutable), operations projection, activation; validate (dry-run) endpoint with readable errors
- [ ] UI: projects list, project overview, spec explorer, endpoint detail, import dialog

### Phase 3 — Environments, outbound safety, ad hoc execution
- [ ] Environments, variables, AES-GCM secrets, redactor
- [ ] `outbound` module: host parser, address classifier, guarded DNS resolver, HttpClient 5 config, manual redirects, size/time limits, capped decompression, header validation, trusted targets
- [ ] Execution policy; request builder; variable resolver; auth application
- [ ] Ad hoc execution and history persistence (redacted snapshots), error classification
- [ ] `demo-target` app + spec with documented deviations
- [ ] Tests: full SSRF suite, limits, redaction, log capture
- [ ] UI: environments page, endpoint "send request" panel, execution detail

### Phase 4 — Contract validation
- [ ] Spike: openapi-request-validator mapping, 3.1 behaviour, regex deadline (record in DECISIONS.md)
- [ ] `ContractValidator`, finding mapper, `OutcomeEvaluator` (exhaustive tests); validation report endpoint
- [ ] UI: findings table, validation report view

### Phase 5 — Deterministic test generation and runs
- [ ] Test cases (origin, review, lifecycle, risk, dedupe); generators per category; golden-file tests
- [ ] Async runs (bounded executor, cancel, interrupted recovery, per-user caps); re-import stale marking
- [ ] UI: test generation workspace, test editor, run dialog, run results, execution history

### Phase 6 — Coverage and dashboard
- [ ] Coverage service with defined metrics; dashboard endpoint
- [ ] UI: coverage report, overview dashboard statistics

### Phase 7 — AI (Ollama via Spring AI)
- [ ] `AiGateway`, context sanitizer, output schemas and validator, fallbacks, concurrency/timeouts
- [ ] Suggestions → `PENDING_REVIEW` with review gate; failure explanations with labelled hypotheses and citations
- [ ] Model evaluation; stub-model ITs; UI for suggestions and the failure assistant

### Phase 8 — Hardening and release
- [ ] Backend/frontend Dockerfiles, nginx, full Compose stack (Ollama profile), non-root images
- [ ] Playwright E2E in CI; security review against SECURITY.md; accessibility pass
- [ ] Completion definition verified item by item

## Completion definition tracker

| Criterion | Status |
|---|---|
| Frontend and backend run together | ✅ (dev mode, Phase 1) |
| PostgreSQL persists real application data | ◐ Database and migrations persist; no application data exists yet |
| Valid OpenAPI spec can be imported and explored | ☐ |
| Real tests generated and executed against an authorized test API | ☐ |
| Contract validation produces explainable results | ☐ |
| Test history and coverage persist | ☐ |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ☐ |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run (no remote) |
| Docker-based local setup works | ◐ PostgreSQL only; full stack in Phase 8 |
| README has reproducible setup and usage | ✅ for Phase 1 scope (verified from a clean checkout) |
| No unresolved critical defects or falsely advertised features | ✅ README states exactly what exists |
