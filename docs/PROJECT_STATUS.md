# Project Status

**Current phase**: Phase 12 — Documentation, demo and release — **complete** (2026-10-23)
**Release status**: feature-complete release candidate. Every mandatory local check passed on a clean clone (below); the remaining step is the first GitHub Actions run after pushing.
**Runnable today**: the complete stack with one command (`docker compose --profile demo up -d --build`) and the documented demo ([DEMO.md](DEMO.md)); development from source; optional local AI (native Ollama or the `ai` profile).

## Review after release (2026-10-29)

An audit of the code against its documentation and a focused security review of the outbound path, authorization,
secrets handling and the build gates. The end-to-end workflow already worked (Phase 12), so no feature was rebuilt.

| Finding | Severity | Resolution |
|---|---|---|
| IPv4-translated IPv6 addresses (`::ffff:0:a.b.c.d`, SIIT) were classified as public, so `http://[::ffff:0:7f00:1]/` or a DNS answer `::ffff:0:a9fe:a9fe` passed the outbound policy. Exploitable only where the network translates that prefix | Low (defense in depth) | Fixed test-first: 4 classifier and 3 policy cases failed before the change and pass after it; `3fff::/20` (RFC 9637 documentation) added |
| The JaCoCo coverage gates described in TESTING.md and the README were reported but not enforced (no `check` goal) | Medium (documented but missing) | `jacoco:check` added to `verify` with the documented thresholds; shown to fail at a higher threshold and pass at the documented one |
| An open public instance lets anyone who registers send requests to public hosts | Medium (deployment guidance) | Documented as residual risk R1 with the mitigation (registration off), also in README and DEPLOYMENT.md |
| Block messages name the category of a resolved address | Low | Accepted and documented (R2) |
| Threat model cited five test classes that do not exist (planning names); the dependency matrix listed WireMock and Recharts, which are not dependencies | Documentation | Mapped to the real tests; matrix corrected |

Also added: `CLAUDE.md` (rules for AI coding agents, from the verified conventions), a README table of what the model decides and what it never decides, and a section on how the project was built.

Verification (executed 2026-10-29, Windows 11, Docker Desktop 29.9.1): `./mvnw -B clean verify` before the changes — 868 tests, BUILD SUCCESS, 94.0% / 80.1%; after — **875 tests, 0 failures, "All coverage checks have been met", BUILD SUCCESS**, 94.0% line / 80.1% branch; outbound security tests 175/175. Frontend `npm run check` 125/125, `npm run build` OK, `npm audit --omit=dev` 0 vulnerabilities. OSV-Scanner 2.6.0 on a fresh SBOM (132 components) and both lockfiles: no issues. gitleaks on the changed files: no leaks. Not re-run (no workflow or UI change): end-to-end suite, demo, stack checks (last passed 2026-10-23). GitHub Actions results could not be read from here (private repository, no `gh`).

**CI failure fixed (2026-10-30).** GitHub reported the `stack` job (*Images, complete stack and end-to-end tests*) failing. Reproduced locally exactly as the workflow runs it (export of commit `8298863`, images tagged `ci`, the workflow's `.env` with `APIFORGE_AI_ENABLED=false`, `--no-build`, the same health checks, `npm ci`, typecheck, the full suite): the stack started and the health, management-endpoint and migration checks passed; the end-to-end suite had **10 passed, 1 skipped, 1 failed** — the no-AI journey expected the header to read "AI: … Disabled", but the UI labels AI switched off by configuration as "AI: Off". Local runs had only covered "no AI" with Ollama stopped (*Unavailable*), so the assertion had been wrong since Phase 10 and the job had failed since Phase 11 added it. The test now expects *Off*; re-run against the same stack: **11 passed, 1 skipped** (the AI-available journey). The `backend` job, which the `stack` job waits for, passed on GitHub, so the new coverage gate holds in CI too. Remaining difference from CI: the local run used Microsoft Edge instead of Playwright's bundled Chromium.

**ADR-031** records the decision that verdicts are deterministic and the model only assists, with the rejected model-driven alternative and where it is enforced.

## Phase 12 summary — release

### Delivered

| Requirement | Implementation |
|---|---|
| README: overview, features, architecture and database diagrams | README.md rewritten: overview, feature list, architecture (Mermaid) with the technology table, data model (Mermaid ER), contents |
| Setup and environment variables | Docker quick start, development setup from source, configuration table (full reference in DEPLOYMENT.md §3, every variable in `.env.example`) |
| Example API requests and responses | README API example; docs/API_EXAMPLES.md with exchanges captured from the running stack (registration with CSRF, import, generation, environment, blocked destination, run, results, coverage, AI status, errors) |
| Import, generate, inspect contract failures; Ollama and AI | README "Using APIForge" and "AI with Ollama"; docs/AI.md |
| Screenshots of working screens | docs/screenshots/01–10, captured from the clean-clone stack while executing docs/DEMO.md |
| Safe, reproducible demo | docs/DEMO.md against the bundled Warehouse Lab sample API (local only; one deliberate violation, more with `APIFORGE_DEMO_VIOLATIONS=1`) |
| Limitations, security decisions, troubleshooting | README sections plus SECURITY.md and DEPLOYMENT.md |
| Repository review for secrets and generated files | gitleaks 8.30.1 over the full history and the working tree (four reviewed non-secrets and one captured request id in `.gitleaksignore`); a `gitleaks` CI job; no build output or `.env` tracked |
| License matching dependencies and assets | MIT LICENSE; THIRD_PARTY_NOTICES.md from the backend SBOM (132 runtime components) and the frontend production dependencies (98 packages); permissive or weak-copyleft libraries used unmodified; the Geist font's OFL text ships with the build at `/licenses/geist-font-OFL-1.1.txt` |
| Project summary and resume bullets | docs/PORTFOLIO.md, every number traced to an executed result |

### Final completion check (executed 2026-10-23; Windows 11, Docker Desktop 29.9.1, Ollama 0.40.8)

Clean clone of commit `9d07910` into an empty directory, fresh `.env` per the README, new Compose project with new volumes; the release fixes below were then applied to the clone and re-verified.

| Check | Result |
|---|---|
| Installation as documented (`docker compose --profile demo up -d --build`) | ✅ images built, services healthy, V1–V13 applied |
| Demo workflow (docs/DEMO.md, scripted in the browser) | ✅ 12/12 steps with the documented numbers: 7 endpoints, 128 tests, 127 passed / 1 failed with the documented assertions, AI explanation (`qwen2.5:7b`), 115 passed / 13 failed in violations mode with the regressions in the comparison, coverage 7/7 operations, 24/25 responses, 105/105 constraints, 27/27 negative kinds, AI suggestions held for review |
| Backend `./mvnw -B verify` | ✅ 868 tests, 0 failures, 94.0% line / 80.1% branch coverage |
| Frontend `npm ci`, `npm run check`, coverage, `npm run build` | ✅ 0 vulnerabilities, 125/125 tests, 90.55% lines, build including the font license |
| Stack checks (scripted, 36) | ✅ 36/36 after the fixes (images inspected: `apiforge-*:local`) |
| End-to-end suite, native Ollama | ✅ 11 passed, 1 skipped (the no-AI journey) |
| End-to-end suite, AI unavailable | ✅ 11 passed, 1 skipped (the AI journey) |
| `ai` profile (Ollama in the stack) | ✅ `ollama-pull` fetched `llama3.2:3b` into the volume (kept across restarts, port not published), status READY via `http://ollama:11434`; AI journey passed with `APIFORGE_AI_REQUEST_TIMEOUT=600s` and `APIFORGE_PROXY_READ_TIMEOUT=1300s` (CPU only: 167–209 s per suggestion request). With the default 120 s the backend answered with its clean timeout error |
| Development setup from source | ✅ `./mvnw spring-boot:run` (dev profile, `.env` read, 13 migrations validated, health UP) and `npm run dev` proxying `/api` and `/actuator` to it |
| Security scans | ✅ OSV-Scanner 2.6.0 on the CycloneDX SBOM and both lockfiles: no issues; gitleaks: no unreviewed findings in history or working tree; actionlint 1.7.12: no findings |
| README claims | ✅ audited below |

### Every phase re-checked on the final code (2026-10-23)

Each phase's own completion-check script was run again against the final backend jar (dev profile, the development database, Vite, the Warehouse Lab sample and native Ollama). Scripts written before the Phase 9 redesign were adapted only where the navigation or wording changed (opening a project now lands on its Overview; status messages read "The test expects 400, but the response is 201."; generator v2); no expectation about behaviour was relaxed.

| Phase | Completion check | Result |
|---|---|---|
| 0 | Design documents, Mermaid diagrams, threat model, decisions | ✅ all 8 documents present; architecture and ER diagrams; 30 ADRs with the dependency matrix |
| 1 | Builds, PostgreSQL, Flyway, health, frontend → backend, no secrets, clean checkout | ✅ covered by the clean-clone run above (build, V1–V13, health UP, Vite proxy, gitleaks) |
| 2 | Registration, login, invalid credentials, unauthorized and cross-user access, validation, persistence | ✅ 12/12 |
| 3 | Import the real Petstore sample, verify the inventory, refresh, persistence | ✅ 15/15 (19 endpoints) |
| 4 | Generated tests valid, explainable and repeatable | ✅ 19/19 (128 tests; regeneration 0 new / 0 updated, edits kept) |
| 5 | Success, HTTP errors, timeouts, malformed responses, blocked destinations, redirects, redaction | ✅ 25/25 |
| 6 | Reported failures equal the actual contract violations | ✅ 27/27 (127/1 normal, 115/13 violations mode) |
| 7 | Dashboard equals hand-calculated metrics | ✅ 48/48 |
| 8 | Live model, Ollama stopped, malformed output | ✅ 27/27 (11 live with `qwen2.5:7b`, 9 offline, 7 malformed) |
| 9 | Every major workflow with a real account | ✅ 73/73, axe: no violations on 13 pages, no mobile overflow |
| 10 | Mandatory test suites | ✅ clean-clone results above (868 backend, 125 frontend, e2e in both AI modes) |
| 11 | Complete stack from a clean environment | ✅ clean-clone results above (36/36 stack checks, `ai` profile) |

During this re-check the locally installed JVM (Oracle JDK 21.0.8) crashed once with an access violation inside JIT-compiled code (no `hs_err` file; Windows reports module "unknown", as in one earlier crash on 2026-10-21). Java code cannot cause this, the container images (Temurin 21.0.12) never showed it, and the interrupted check passed after a restart. See the known limitations.

### README claims audit

| Claim | Evidence |
|---|---|
| Import (3.0/3.1, JSON/YAML, upload/paste, dry run), safe parsing, explorer | Demo step 3; Phase 3 tests; Petstore's 19 operations counted in `samples/petstore3-openapi.yaml` |
| Deterministic generation, single-fault negatives, reasons, idempotent regeneration | Demo step 4 (128 tests); Phase 4 completion check |
| Environments, secrets write-only and encrypted, guarded outbound client | Demo step 5 (`•••••• (write-only)`), API_EXAMPLES blocked metadata URL (400), e2e `safety.spec`, 168 outbound tests |
| Contract validation with path, expected, actual; honest outcomes; comparison | Demo steps 6–7, API_EXAMPLES result detail, e2e `contract-testing.spec` |
| Coverage metrics | Demo step 8 numbers |
| AI suggestions held for review, explanations, works without Ollama | Demo steps 6 and 9, e2e `ai.spec` in both modes, `ai` profile run |
| Technology versions (Spring Boot 4.1, Hibernate 7, React 19, TypeScript 6, Vite 8, Tailwind 4, PostgreSQL 18, 13 migrations) | `pom.xml`, resolved `hibernate-core` 7.4.5, `frontend/package.json`, Flyway log |
| Test numbers (868 / 125 / 12 / 36) | Clean-clone runs above |
| Non-root, read-only containers; database and Ollama not published; CSP | Stack checks; Phase 11 read-only verification |
| Warnings are errors; `npm run check` = lint, type check, tests | `failOnWarning` in `backend/pom.xml`; `frontend/package.json` |
| Troubleshooting: missing secrets key, `Secure` cookies over HTTP, AI timeouts | Reproduced: the startup message names `APIFORGE_SECRETS_KEY` / `apiforge.secrets.key`; the AI timeout message is the one `OllamaGateway` returns |
| CI workflows | Configured and linted, **not yet run on GitHub** (stated as such in README and PORTFOLIO.md) |

### Defects found and fixed during this phase

- **nginx kept a stale backend address**: it resolved `backend` once at startup, so recreating the backend container (new IP) produced 502s until nginx restarted. It now resolves per request through Docker's DNS (`resolver`, variable `proxy_pass`); verified by recreating the backend with a new address while the frontend kept running.
- **nginx cut long AI requests**: its fixed 180 s read timeout was shorter than an AI call may take (up to two attempts of `APIFORGE_AI_REQUEST_TIMEOUT`), so a slow model produced a 504 in the browser while the backend still succeeded (209 s). Now `APIFORGE_PROXY_READ_TIMEOUT` (default 300 s), documented with CPU-only values.
- **The demo's violations mode needed a manual container command**: `APIFORGE_DEMO_VIOLATIONS` now switches it from `.env`.
- gitleaks flagged a request id in a captured response (reviewed and listed); the demo capture script took screenshots while a toast or the previous run's page was still shown (fixed in the script).

### Known limitations (release)

- **GitHub Actions not yet run on GitHub**: the workflows are linted and their steps were executed locally; confirm the first run after pushing before citing CI results.
- **Single backend instance**: sessions and rate limits in memory; a restart signs users out.
- **Scope**: OpenAPI 3.0/3.1 HTTP/JSON APIs; multi-file specifications must be bundled; at most 500 tests per run.
- **AI depends on hardware**: CPU-only inference needs longer timeouts (167–209 s per suggestion request with `llama3.2:3b`); suggestions always need review.
- **Verified on one machine** (Windows 11, Docker Desktop, Edge). No deployment to a real cloud provider or Let's Encrypt certificate was performed; no free tier reviewed offers a dependable database plus memory for local AI.
- **No e-mail features** (password reset, e-mail change).
- **Local JVM crash (environment)**: the from-source backend on this machine's Oracle JDK 21.0.8 crashed twice in several days of running (native access violation in JIT code). Updating the local JDK to the current Java 21 update is advised; the Docker images use Temurin 21.0.12.
- Login (10/min) and registration (5/min) rate limits apply per IP, so back-to-back end-to-end runs need a minute between them.

## Phase 11 summary (previous phase)

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
- *(Resolved in Phase 12: pulled and verified after Docker's disk moved to another drive.)* **Containerized Ollama (`ai` profile) not pulled on this machine**: the image is 3.4 GB compressed and the system drive had too little free space; AI connectivity from the container was verified with the native Ollama instead. Moving Docker Desktop's disk image to a larger drive would allow it.
- A fully free deployment is not dependable with the providers reviewed (DEPLOYMENT.md §11); no free tier offers the memory for local AI.
- Single backend instance (in-memory sessions and rate limits).

## Phase 10 summary

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

- **Phase 12 (2026-10-23)** — Release: README, demo workflow, API examples, screenshots, MIT license with third-party notices, gitleaks in CI, portfolio summary; final completion check on a clean clone (demo 12/12, 868 + 125 tests, e2e in both AI modes, 36 stack checks, `ai` profile); nginx runtime DNS and proxy read timeout fixed. Committed as `9d07910` plus the release-verification commit.
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
- [x] Phase 12 — Documentation, demo and release

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
| Auth, authorization and outbound safeguards tested | ✅ incl. 175 outbound security tests and execution ITs |
| Every major workflow works in the UI with persisted data | ✅ Phase 9 (73/73 walkthrough checks, axe 0 violations, no mobile overflow) |
| Automated tests and CI pass | ◐ All local suites pass on a clean clone (868 backend, 125 frontend, 12 e2e journeys, 36 stack checks); GitHub CI not run from here |
| Docker-based local setup works | ✅ Phase 11 (complete stack from a clean environment, persistence, health checks, HTTPS) |
| README has reproducible setup and usage | ✅ Phase 12 (clean-clone installation, demo and development setup executed; claims audited) |
| No unresolved critical defects or falsely advertised features | ✅ |
