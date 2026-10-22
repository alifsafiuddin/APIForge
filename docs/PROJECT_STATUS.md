# Project Status

**Current phase**: Phase 10 — Automated testing and quality assurance — **complete** (2026-10-22)
**Next phase**: decided by the next phase brief (planned: hardening and release)
**Runnable today**: every workflow from sign-up to coverage, with optional local AI; now covered by 868 backend tests, 125 frontend tests and 11 end-to-end journeys, with patched dependencies and the QA results in [QA_REPORT.md](QA_REPORT.md).

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

## Phase 9 summary (previous phase)

### Delivered

| Requirement | Implementation | Where |
|---|---|---|
| Login, registration, logout | Existing forms reviewed (client checks mirror server rules, server field errors on their fields); sign-out from the account menu and Settings | `features/auth`, `features/settings` |
| Dashboard and project overview | `GET /api/v1/dashboard` and `GET /projects/{id}/overview`: SQL aggregates over stored rows (no estimates, no cache). Home page: totals, most recently active projects with specification, tests to review, stale tests, environments, last run and the latest **stored** coverage snapshot (with its time), recent runs, system status, New project. Project Overview tab (now the project's landing page): setup checklist derived from the project's state, key numbers linking to their tabs, recent runs, Run tests | `dashboard` module, `OverviewPage`, `ProjectOverviewPage` |
| Project creation | Create from the dashboard (opens the new project) or the projects page | `ProjectFormDialog` |
| Specification upload/validation, explorer | Unchanged from Phase 3, re-verified in the walkthrough (dry-run check, import, operation detail, reload) | `features/specs` |
| Test suite creation and management, generation | Suites dialog and bulk move (Phase 4); runs can now target **one suite** | `RunTestsDialog` |
| Search, sorting, pagination | Project-wide **test finder**: name search (`q`), views All / Needs review / AI-suggested / Stale / Disabled, pages of 25, all kept in the URL. **Run history filters**: kind, status, result (`failing`) on new backend parameters, kept in the URL, with paging | `AllTestsPanel`, `RunsPage`, `TestRunRepository.search` |
| AI suggestions and review | Phase 8 panel; the review queue is reachable as a view of the finder | `AiSuggestionsPanel`, `?view=review` |
| Environment/auth configuration, execution, contract details, failure explanation, coverage, history | Existing screens, re-verified end to end | — |
| User settings and logout | **Settings** page: display name (`PATCH /auth/me`), password change (`POST /auth/password`; ends every other session via `users.credentials_changed_at`, this session renewed), sign-out, account deletion with typed e-mail and password (`DELETE /auth/me`; cascades to projects). Password checks limited to 5 per user per minute; audited | `AccountService`, `CredentialEpochs`, `AbsoluteSessionTimeoutFilter`, V13 |
| Confirmation for destructive actions | Shared `ConfirmDialog`: deleting an environment (was a native `confirm`) or a variable (was immediate, its error unhandled), cancelling a run; typed confirmation for project and account deletion | `components/ConfirmDialog` |
| Loading, empty, offline, error, success states | Skeletons, empty states with the next action, errors with Try again, toasts; a **connection banner** for offline (browser) and unreachable backend, re-checked as soon as any request fails with a network error and cleared when health answers | `ConnectionBanner`, `query-client` |
| Responsive, consistent, accessible | Mobile header fixed (logo and status dots only below `sm`, labels kept for screen readers); headings in order; colour tokens darkened where axe found contrast below 4.5:1 (`--destructive`, `--muted-foreground`, amber notes); fieldsets with legends for filter groups; sidebar entry and account-menu item for Settings | `AppShell`, `index.css` |
| No fake statistics or dead controls | Every number on the dashboard and overview is counted by the backend and compared with the API in the walkthrough; the unused `RecentProjectsCard` was removed; disabled controls say why | — |

### Verification (executed 2026-10-22; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8, Ollama 0.40.8 with `qwen2.5:7b`)

| Check | Method | Result |
|---|---|---|
| Backend build and tests (clean checkout) | `git checkout-index` of the staged tree into an empty directory, `mvnw verify` | ✅ **816 tests, 0 failures, 0 errors, 0 skipped**; all lint warnings as errors; enforcer and architecture rules pass; line coverage 93.1% (6749/7252), branch 78.6%; `dashboard` 100% lines, `auth` 97.0%. The first clean run had **18 errors**: `@WebMvcTest` slices include the session filter, which now needs `CredentialEpochs` (fixed with a slice test configuration) |
| Frontend (clean checkout, while the backend build ran) | `npm ci`, `npm run check`, coverage, `npm run build` | ✅ 0 vulnerabilities; 0 lint findings; tsc clean; **116/116 tests** in both runs; lines 89.9%, branches 77.4%; build without warnings. The first clean run had 2 tests time out at Testing Library's 1 s default under that load; the wait is now 4 s (`src/test/setup.ts`) |
| Migration on existing data | Backend jar against the Phase 8 dev database | ✅ V12 → V13 applied |
| **Completion check**: every major workflow with a real account and persisted data | Headless Microsoft Edge against the running stack (backend, PostgreSQL, the Warehouse Lab server, Ollama), fresh account, 18 workflow steps: register; create the first project from the dashboard; dry-run check and import, explorer, reload; generate 128 tests, finder count and name search equal to the API, reload keeps the search, test sheet; toggle a test (persisted) and move tests into a new suite; AI suggestions (5 returned, one accepted, "Needs review" view equal to the API); environment and write-only secret through the form; a **suite run** (exactly the suite) and a full run (129 tests, 1 deliberate contract failure); assertion table and AI explanation, both kept after reload; run comparison; history filter equal to the API, kept after reload, Back restores the unfiltered list, Clear filters; coverage (7/7 operations); console request; dashboard and project overview numbers equal to the API (2 runs in 7 days, 1 with failures, 4 to review, 133 active tests); rename persisted; wrong current password on its field; password change keeps this session and **signs out a second browser**; old password rejected, new one accepted; confirmations for variable and environment deletion (cancel keeps the secret); a second account deleted with typed e-mail can no longer sign in; offline and unreachable-backend banners appear and clear; 13 mobile pages at 390 px; skip link | ✅ **73/73 checks, 18/18 steps**; **axe-core: 0 violations of any impact** on 13 pages (overview empty and with data, project overview, projects, operation detail, tests, environments, runs, run detail, coverage, console, settings, login); **0 px horizontal overflow** on all 13 mobile pages; no JavaScript errors. Earlier runs of the same check found the contrast and heading problems listed below |
| Secrets and audit | `psql` and the backend log after the runs | ✅ The demo key appears 0 times in the log, AI explanations, stored results and audit events; profile updates, password changes and account deletions are audited |

### Defects found and fixed during this phase

- **Colour contrast below WCAG AA** (axe): destructive buttons and badges (`--destructive` on its 10% tint), muted text on tinted rows in the endpoint explorer and body inspector (`--muted-foreground`), amber notes on the dashboard. Tokens darkened.
- **Heading levels skipped** on the endpoint explorer and the console result (h1 → h3); section headings added.
- **Variable deletion** happened on one click and its failure was an unhandled promise rejection; now confirmed and reported.
- **Environment deletion** used the browser's native `confirm`; now the application's dialog.
- **Mobile header overflowed by 9 px** on every page (brand name, AI chip, status label and avatar at 390 px).
- `/projects/:id` only redirected to the specification; there was no project overview. The home page showed only recent project names.
- A first version of the connection re-check invalidated the health query when the health query itself failed — an endless refetch loop (it crashed a test worker); the health check is now excluded.
- The clean-checkout errors described above (slice tests, test timeouts under load).

### Known limitations

- The dashboard lists the 12 most recently active projects; the projects page lists all.
- Coverage on the dashboard and overview is the latest stored snapshot (taken after generation, runs and activation); the coverage page is always current.
- The credential-change cache is in memory, like sessions and rate limits (single instance, ADR-008).
- E-mail addresses cannot be changed; there is no password reset by e-mail (no mail infrastructure).
- Dark mode follows the operating system (no in-app switch); its colours were not checked with axe in the walkthrough.
- GitHub CI status for this branch is checked by the user.

## History

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
| AI suggestions and explanations work through a local model | ✅ Phase 8 (`qwen2.5:7b`, evaluated with `llama3.2:3b` too) |
| AI outages do not break deterministic testing | ✅ Phase 8 (verified with Ollama stopped) |
| Auth, authorization and outbound safeguards tested | ✅ incl. 168 outbound security tests and execution ITs |
| Every major workflow works in the UI with persisted data | ✅ Phase 9 (73/73 walkthrough checks, axe 0 violations, no mobile overflow) |
| Automated tests and CI pass | ◐ All local suites pass (868 backend, 125 frontend, 11 e2e journeys); GitHub CI not run from here |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
