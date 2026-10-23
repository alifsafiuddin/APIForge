# Project Status

**Current phase**: Phase 11 — Docker, cloud deployment and CI/CD — **complete** (2026-10-23)
**Next phase**: decided by the next phase brief
**Runnable today**: the complete stack with one command (`docker compose up -d --build`): PostgreSQL with persistent storage, the backend and the frontend as hardened containers, optional Ollama, HTTPS through Caddy; CI builds the images and runs the end-to-end tests against that stack. Deployment options, backups, monitoring and free-tier limits are in [DEPLOYMENT.md](DEPLOYMENT.md).

## Phase 11 summary

### Delivered

| Requirement | Implementation |
|---|---|
| Production-ready Dockerfiles | `backend/Dockerfile` (multi-stage, Temurin 21.0.12.1 JRE Alpine, layered jar, non-root uid 10001, `prod` profile, container memory settings, readiness health check); `frontend/Dockerfile` (Node 24.22.0 build, `nginx-unprivileged` 1.30.6, health check). Base images pinned to exact versions |
| Compose for local use with PostgreSQL and Ollama | `docker-compose.yml`: base stack plus profiles `ai` (Ollama 0.40.8, model volume, one-shot model pull), `tls` (Caddy 2.11.7), `demo` (sample API as trusted target) |
| Persistent storage, health checks, startup order | Named volumes `pgdata`, `ollama`, `caddy-*`; `postgres` → `backend` → `frontend` → `caddy` gated on health; restart policies; log rotation |
| No credentials in images | Configuration only from the environment; `.dockerignore` files; verified by inspecting image history, environment and filesystem |
| Production environment variables | `.env.example` (stack section), DEPLOYMENT.md §3; `prod` profile requires every secret, trusts forwarding headers only from private proxies |
| GitHub Actions | `ci.yml` (+ `stack` job: images, complete stack from a clean environment, migrations and endpoint checks, end-to-end tests), `dependencies.yml` (OSV-Scanner on SBOM and lockfiles, weekly), `release.yml` (images to GHCR on tags), Dependabot (Maven, npm, Docker, Compose, Actions); validated with actionlint |
| Migration strategy | DEPLOYMENT.md §6.1 (Flyway, forward-only, expand/contract, backup before upgrade) |
| Actuator without sensitive endpoints | Only `health` and `info` exposed; through nginx only the aggregated `/actuator/health`; backend port not published |
| Logging and error monitoring guidance | DEPLOYMENT.md §13 (ECS JSON to stdout, request ids, alerts, optional error service without request data) |
| Backup and recovery | DEPLOYMENT.md §6.2, with the secrets key backed up separately; procedure executed |
| Hosting, Ollama resources, outbound networking, free-tier limits | DEPLOYMENT.md §9–§12, provider terms checked 2026-10-23 |
| Private, protected AI endpoint | Ollama never published; remote models over a private network/VPN only |
| No public database | PostgreSQL on loopback (or not published); managed databases over private networking or TLS + allow-list |

### Verification (executed 2026-10-22/23; Windows 11, Docker Desktop 29.9.1, Ollama 0.40.8 native)

| Check | Result |
|---|---|
| **Complete stack from a clean environment**: `git checkout-index` into an empty directory, fresh `.env` with generated secrets, new Compose project (new volumes), `docker compose --profile demo up -d --build` | ✅ Images built from source; services started in order and became healthy |
| Stack checks (scripted, 36) | ✅ **36/36**: services healthy; **V1–V13 applied** to the empty database; aggregated health `UP` without details; `/actuator`, `/actuator/info`, `/env`, `/heapdump`, `/loggers`, `/health/readiness` → 404 at nginx; backend not reachable from the host; database bound to loopback; CSP, `X-Frame-Options`, `nosniff`, hidden nginx version, SPA fallback, immutable assets; registration, authenticated API call and CSRF refusal through the frontend origin; session cookie `HttpOnly; SameSite=Lax`; **AI status READY from inside the backend container** (native Ollama via `host.docker.internal`); both containers non-root; no `.env` or secret in either image; ECS JSON logs without errors |
| **Frontend connectivity** (end-to-end suite against the stack, `E2E_BASE_URL=http://localhost:8080`) | ✅ 11 passed, 1 skipped (the no-AI journey, AI being available) — three times: after startup, after the forwarding-header fix, and with read-only containers; includes the new check that pages raise no CSP violations or console errors under the production policy |
| **Database persistence** | ✅ `docker compose down` (containers removed, volumes kept) and `up`: account and project still there, sign-in works, Flyway "up to date"; also after a full Docker Desktop restart |
| Backup and restore (DEPLOYMENT.md §6.2, as written) | ✅ `pg_dump` → backend stopped → database dropped and recreated (0 tables) → `pg_restore` → backend started: users, projects, tests and runs identical (8/9/522/2), sign-in 200 |
| HTTPS (`tls` profile, `APIFORGE_DOMAIN=localhost`, Caddy local CA) | ✅ `http` → 308 to `https`; HSTS; HTTP/3 advertised; cookies `Secure` (session `HttpOnly; SameSite=Lax`, CSRF `SameSite=Strict`); registration and session over TLS |
| Client address behind proxies | ✅ after the fix: a spoofed `X-Forwarded-For` is ignored directly at nginx and through Caddy; with `APIFORGE_PROXY_TRUSTED=true` behind Caddy the real client address is recorded |
| Read-only containers | ✅ backend, frontend, Caddy and the demo target refuse writes to their root filesystem; the stack stays healthy and passes all checks |
| Backend and frontend (clean checkout) | ✅ 868 backend tests, 0 failures, no compiler warnings; 125/125 frontend tests, lint and type check clean, build without warnings, 0 vulnerabilities |
| Workflows | ✅ `actionlint` 1.7.12: no findings; OSV-Scanner Linux binary checksum verified by downloading it |

### Defects found and fixed during this phase

- **Clients could choose their recorded address**: through Docker's port publishing every client appears to come from a private gateway address, which the backend trusts as a proxy, so a forged `X-Forwarded-For` reached rate limits and the audit log (recorded `6.6.6.6`). nginx now replaces forwarding headers unless `APIFORGE_PROXY_TRUSTED=true`.
- `/actuator` (no trailing slash) fell through to the SPA (no backend exposure, but not the intended 404).
- Required-variable checks on the backend and Caddy would have broken `docker compose up -d postgres` for development and the existing CI job (Compose interpolates every service); the backend's own startup check is used instead.
- `.env`'s `OLLAMA_BASE_URL=http://localhost:11434` (for development) would have pointed the containerized backend at itself; the stack uses `APIFORGE_STACK_OLLAMA_URL`.
- The frontend would have reported CSP violations: zod probes `new Function`; it now runs `jitless`.
- The in-memory `conf.d` of the read-only nginx container was not writable by the non-root user.
- DEPLOYMENT.md claimed health details for authenticated users in `dev` (not configured).

### Known limitations

- **Not run here**: the GitHub workflows (they run on GitHub; `actionlint` validated them), a deployment to a real cloud provider and a Let's Encrypt certificate (no account or public domain in this environment; the tls profile was verified with Caddy's local CA).
- **Containerized Ollama (`ai` profile) not pulled on this machine**: the image is 3.4 GB compressed and the system drive had too little free space; AI connectivity from the container was verified with the native Ollama instead. Moving Docker Desktop's disk image to a larger drive would allow it.
- A fully free deployment is not dependable with the providers reviewed (DEPLOYMENT.md §11); no free tier offers the memory for local AI.
- Single backend instance (in-memory sessions and rate limits).

## Phase 10 summary (previous phase)

The full report — commands, outcomes, defects, security checks, limitations and checks not run — is [QA_REPORT.md](QA_REPORT.md).

### Delivered

| Requirement | Implementation |
|---|---|
| Backend unit, controller-contract, integration and Testcontainers tests | New: `ApiContractIntegrationTest` (HTTP contract of every error class and of created resources and pages), `SpecificationEdgeCasesIntegrationTest`, `EnvironmentRulesIntegrationTest` (incl. the previously untested environment deletion), `ExecutionRulesIntegrationTest`, `SchemaResolverTest`, `AllOfSynthesisTest`, `ClockConfigTest`; 816 → 868 tests |
| Auth, authorization, SSRF, secret redaction, AI failure handling | Existing suites re-run; new `SecretLoggingIntegrationTest` proves the "never in logs" claim |
| Frontend component, form-validation and API-error tests | New `ConsolePage.test.tsx`, `ErrorHandling.test.tsx`; 116 → 125 tests |
| End-to-end tests of critical journeys | New `e2e/` Playwright project (strict type check): contract testing, authentication and sessions, outbound safety and confirmations, AI with and without Ollama, offline/unreachable states and mobile layout |
| Static analysis, vulnerability checks | SpotBugs and knip (findings fixed or justified), OSV-Scanner on the backend SBOM, lockfiles and the PostgreSQL image, `npm audit` |
| Logs, fake results, dead code, misleading text | Log and database reviews clean; no fake data; unused test starters and types removed; outdated docs corrected |
| Warnings fixed, not suppressed | No suppression added; one attempted `@SuppressWarnings` was replaced by code that needs none |

### Verification (executed 2026-10-22; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8, Ollama 0.40.8)

| Check | Result |
|---|---|
| Backend, clean checkout (`mvnw verify`) | ✅ **868 tests, 0 failures, 0 errors, 0 skipped**; no compiler warnings; enforcer and architecture rules pass; lines 94.0% (6827/7266), branches 80.1% |
| Frontend, clean checkout (`npm ci`, lint, typecheck, coverage, build, audit) | ✅ **125/125 tests**; 0 lint findings; lines 90.6%, branches 78.5%; build without warnings; 0 vulnerabilities |
| **End-to-end**, against the clean-checkout jar | ✅ With Ollama: 10 passed, 1 skipped (no-AI journey). Ollama stopped: 10 passed, 1 skipped (AI journey). All 11 journeys pass in their mode |
| Dependency vulnerabilities | ✅ backend SBOM, frontend and e2e lockfiles clean after the Tomcat and Jackson upgrades (were 17 findings, 3 critical); ⚠️ PostgreSQL image: upstream `gosu` findings only (none critical or high) |
| Logs and stored data | ✅ no password, secret, token, cookie or e-mail in the logs of both e2e runs; no secret in results, explanations or audit events |

### Defects found and fixed during this phase

- **Tomcat 11.0.24 and Jackson 3.1.5/2.21.5** had known vulnerabilities (3 critical Tomcat advisories, 7 Jackson advisories); upgraded to 11.0.27, 3.1.7 and 2.21.7.
- **Timestamps differed between a write and the next read** (100 ns clock vs microsecond `timestamptz`); the clock now has microsecond precision. The JDK's `Clock.tick` with sub-millisecond ticks throws in `millis()` — found when the first fix broke every request in tests.
- Possible null dereferences, a vacuous `instanceof`, entity getters exposing persisted maps (SpotBugs).
- Console JSON errors did not name the field; secret-set audit events lacked their action.
- Four unused test starters, ten unused TypeScript types; outdated statements in TESTING, ARCHITECTURE, DEPLOYMENT, SECURITY and ADR-018, documented-but-unread and read-but-undocumented environment variables.

### Known limitations

- End-to-end tests run locally, not in CI (they need the whole stack); Chromium-based Edge only.
- The official PostgreSQL image's `gosu` helper carries Go advisories that only the upstream image can fix.
- The Tomcat and Jackson overrides must be removed once Spring Boot manages these versions.
- GitHub CI status for this branch is checked by the user.

## History

- **Phase 10 (2026-10-22)** — Automated testing and QA: HTTP contract, import edge cases, environment and run rules, schema resolution, secret-logging tests; Playwright end-to-end suite; Tomcat and Jackson upgraded for 17 advisories; microsecond clock; static analysis and dead-code cleanup; QA_REPORT.md. 868 backend and 125 frontend tests, 11 e2e journeys. Committed as `97956ba`.
- **Phase 9 (2026-10-22)** — Complete frontend and user experience (V13): dashboard and project overview from stored numbers, settings (profile, password change ending other sessions, account deletion), project-wide test finder, run filters, suite runs, confirmation dialogs, offline/backend banners, accessibility fixes. 816 backend and 116 frontend tests; walkthrough 73/73, axe 0 violations. Committed as `17edf71`.
- **Phase 8 (2026-10-22)** — Local AI with Ollama (Spring AI 2.0.1, V12): test suggestions stored for review, failure explanations with graded facts and separate hypotheses, structured output validated, graceful degradation, evaluation of two models. 801 backend and 100 frontend tests; completion check 27/27 (live, Ollama stopped, malformed output). Committed as `afd6910`.
- **Phase 7 (2026-10-22)** — Specification coverage (V11): defined metrics with test design kept apart from execution, gaps, filters, drill-down, duplicates counted once, snapshots and changes across versions. 745 backend and 95 frontend tests; completion check 48/48 against hand-calculated metrics. Committed as `dcc870b`.
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
- [x] Phase 8 — Local AI with Ollama (suggestions, explanations, evaluation)
- [x] Phase 9 — Complete frontend and user experience (dashboard, overview, settings, finder, filters, states, accessibility)
- [x] Phase 10 — Automated testing and quality assurance (QA_REPORT.md)
- [x] Phase 11 — Docker, deployment and CI/CD

## Completion definition tracker

| Criterion | Status |
|---|---|
| Frontend and backend run together | ✅ (dev mode) |
| PostgreSQL persists real application data | ✅ users, projects, specifications, operations, test suites and cases, audit trail |
| Valid OpenAPI spec can be imported and explored | ✅ Phase 3 (verified with the official Petstore 3 document) |
| Real tests generated and executed against an authorized test API | ✅ Phase 5 (Warehouse Lab sample: 128 tests executed) |
| Contract validation produces explainable results | ✅ Phase 6 (path, expected, actual and message per finding; verified against deliberate violations) |
| Test history and coverage persist | ✅ Runs, results and assertions persist (Phases 5–6); coverage is computed from them, with snapshots across versions (Phase 7) |
| AI suggestions and explanations work through a local model | ✅ Phase 8 (`qwen2.5:7b`, evaluated with `llama3.2:3b` too) |
| AI outages do not break deterministic testing | ✅ Phase 8 (verified with Ollama stopped) |
| Auth, authorization and outbound safeguards tested | ✅ incl. 168 outbound security tests and execution ITs |
| Every major workflow works in the UI with persisted data | ✅ Phase 9 (73/73 walkthrough checks, axe 0 violations, no mobile overflow) |
| Automated tests and CI pass | ◐ All local suites pass (868 backend, 125 frontend, 11 e2e journeys); GitHub CI not run from here |
| Docker-based local setup works | ✅ Phase 11 (complete stack from a clean environment, persistence, health checks, HTTPS) |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
