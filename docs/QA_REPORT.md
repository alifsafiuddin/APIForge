# QA Report — Phase 10

Quality-assurance pass over the whole application, executed on 2026-10-22 on branch `phase-10-qa`. Every result below comes from a command that was actually run; checks that were not run are listed in §6 with the reason.

**Environment**: Windows 11, Docker Desktop 29.9.1, PostgreSQL 18.6 (Testcontainers and Compose), JDK 21.0.8, Maven Wrapper, Node 24.22.0, Microsoft Edge (Chromium) for browser tests, Ollama 0.40.8 with `qwen2.5:7b`.

## 1. Summary

| Area | Result |
|---|---|
| Backend build and tests (clean checkout) | ✅ **868 tests, 0 failures, 0 errors, 0 skipped**; no compiler warnings (`-Xlint:all` as errors); enforcer and ArchUnit rules pass; JaCoCo gates pass — lines **94.0%** (6827/7266), branches **80.1%** (were 93.1% / 78.6%) |
| Frontend (clean checkout) | ✅ 0 lint findings, type check clean, **125/125 tests**, lines 90.6%, branches 78.5%, production build without warnings |
| End-to-end (Playwright, real stack) | ✅ **11 journeys**: 10 passed + 1 skipped with Ollama running, 10 passed + 1 skipped with Ollama stopped — each journey passed in at least one run, the AI journeys in their matching mode. Run against the jar built by the clean checkout |
| Dependency vulnerabilities | ✅ Backend SBOM (132 runtime components), frontend and e2e lockfiles: **no known vulnerabilities** after upgrading Tomcat and Jackson (17 findings before, 3 critical). ⚠️ PostgreSQL image: 61 findings in the upstream `gosu` helper (none critical or high), see §5 |
| Static analysis | ✅ SpotBugs (max effort, medium threshold): every code finding fixed or justified (§4.3); knip: no unused files or dependencies, dead types removed |
| Logs and stored data | ✅ No password, secret, token, cookie or e-mail address in the backend log after all runs; no secret in stored results, explanations or audit events |
| AI offline | ✅ Whole e2e suite run with Ollama stopped: AI actions disabled with the reason, AI endpoints answer `AI_UNAVAILABLE`, generation, runs, contract validation and coverage unaffected |
| No fake results | ✅ No seeded data, mocks or random data in production code or migrations; no test fixture or MSW code in the production bundle; dashboard numbers compared with the API in e2e |

## 2. Commands executed and outcomes

| # | Command (directory) | Outcome |
|---|---|---|
| 1 | `git checkout-index -a --prefix=<empty dir>/` of the staged tree, then in `backend/`: `./mvnw -B verify` | ✅ BUILD SUCCESS, 868 tests, 0 failures/errors/skipped, 0 compiler warnings, enforcer rules 0–3 passed, JaCoCo check passed |
| 2 | clean checkout `frontend/`: `npm ci` | ✅ 271 packages, 0 vulnerabilities |
| 3 | `npm run lint` (oxlint `--deny-warnings`) | ✅ 0 findings |
| 4 | `npm run typecheck` (`tsc -b`) | ✅ no errors |
| 5 | `npm run test:coverage` | ✅ 21 files, 125/125 tests; statements 89.8%, branches 78.5%, functions 86.6%, lines 90.6% |
| 6 | `npm run build` | ✅ built, no warnings |
| 7 | `npm audit` (frontend, e2e) | ✅ 0 vulnerabilities each |
| 8 | clean checkout `e2e/`: `npm ci`, `npm run typecheck` (strict `tsc`) | ✅ 0 vulnerabilities, no type errors |
| 9 | `e2e/`: `E2E_BROWSER=msedge npx playwright test` with Ollama running, backend = jar from #1 | ✅ 10 passed, 1 skipped (the no-AI journey), 2.4 min |
| 10 | Ollama processes stopped, same command | ✅ 10 passed, 1 skipped (the AI-available journey), 51 s; Ollama restarted afterwards |
| 11 | `backend/`: `./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom -DincludeTestScope=false -DincludeProvidedScope=false -DoutputFormat=json` then `osv-scanner scan source -L backend.cdx.json` (OSV-Scanner 2.6.0, SHA-256 verified) | ❌ first run: 17 vulnerabilities in 5 packages (3 critical, 10 high, 4 medium) → fixed (§3.1) → ✅ rerun: no issues |
| 12 | `osv-scanner scan source -L frontend/package-lock.json -L e2e/package-lock.json` | ✅ no issues |
| 13 | `osv-scanner scan image postgres:18.6-alpine` | ⚠️ stale local image: gosu + Alpine findings; after `docker pull` of the same tag (built 2026-10-21): 61 findings, all in `/usr/local/bin/gosu` (1 medium, 60 unrated) |
| 14 | `./mvnw com.github.spotbugs:spotbugs-maven-plugin:4.10.4.1:spotbugs -Dspotbugs.effort=Max -Dspotbugs.threshold=Medium` | 295 findings → triaged and fixed (§4.3) → rerun: 280 `EI_EXPOSE_REP*`, 3 `VA_FORMAT_STRING_USES_NEWLINE`, 3 `CT_CONSTRUCTOR_THROW`, all justified |
| 15 | `./mvnw dependency:analyze` | 4 unused test starters found and removed; remaining "unused" entries are starters/drivers loaded at runtime (expected) |
| 16 | `npx knip@6.40.0` (frontend) | no unused files or dependencies; 10 unused type aliases removed; remaining unused exports are internal-use values and vendored shadcn/ui components |
| 17 | Log review: `grep` of the backend logs of both e2e runs (2,160 and 1,520 lines) for the test passwords, the lab API key, `APIFORGE_SESSION=`, `XSRF-TOKEN`, `Authorization`, `Bearer`, `X-API-Key`, `api_key=`, e-mail addresses, `ERROR`, `WARN`, `Exception` | ✅ 0 matches each (only the event name `PASSWORD_CHANGED` and the path `/auth/password` mention "password") |
| 18 | `psql`: secrets and passwords in `test_results`, `ai_explanations`, `audit_events`, `environment_variables`; non-bcrypt password hashes | ✅ 0 rows each |
| 19 | Fake-data audit: `grep` of `backend/src/main` for runners, `@PostConstruct`, random and mock data, migrations for `INSERT`, frontend non-test code and `dist/` for MSW and test fixtures | ✅ none (the only `Random` is the seeded, deterministic regex sampler; `SecureRandom` creates IVs) |

During the pass, targeted Maven and Vitest runs were used after each change; the full local suite was also run twice (867 tests, both green) before the clean-checkout build.

## 3. Defects discovered and fixed

### 3.1 Security

- **Vulnerable dependencies** (OSV scan, #11). Spring Boot 4.1.1 (the newest 4.1 release) manages:
  - Tomcat 11.0.24 — `GHSA-9xv2-5v5q-p794` (CVSS 9.8, DIGEST authenticator replay), `GHSA-gcx9-497g-6cp6` (9.1), `GHSA-h3x4-894j-xpx5` (9.1, FORM authentication). APIForge uses neither Tomcat authenticator (authentication is Spring Security), but the library is exposed.
  - Jackson 3.1.5 and 2.21.5 — `GHSA-7hhh-6rmp-j9qf`, `GHSA-p6pp-m3f8-5c89` (ReDoS), `GHSA-cxp5-3px4-pw24`, `GHSA-gx83-3vf8-gh7j`, `GHSA-q4xh-88c3-wmh7`, `GHSA-wjgm-6hv5-3cvf`, `GHSA-wv8q-qhhj-9h54` (denial of service and polymorphic typing issues).
  - **Fix**: `tomcat.version` 11.0.27, `jackson-bom.version` 3.1.7, `jackson-2-bom.version` 2.21.7 in `pom.xml`, each published at least two weeks before; resolved versions confirmed with `dependency:list`, convergence and all tests pass, rescan clean.
- **No test proved "secrets never reach the logs"** (claimed in SECURITY.md). Added `SecretLoggingIntegrationTest`, which captures all output while passwords, tokens and secrets (bearer, query parameter, header) flow through every path. It passes; the outbound log line records host, port and path only, never the query.
- **Audit inconsistency**: setting a secret was audited without an `action`, deleting one with `"action": "deleted"`. Setting now records `"action": "set"`.

### 3.2 Correctness

- **Timestamps changed between write and read.** The application clock has 100 ns precision on Windows; PostgreSQL stores microseconds and rounds. A `POST` returned `…49.798466800Z`, the next `GET` `…49.798467Z`. Found by the new HTTP contract test. **Fix**: a clock truncated to microseconds. The first attempt (`Clock.tick(…, 1 µs)`) failed every request with `ArithmeticException: / by zero`: the JDK's tick clock divides by the tick in whole milliseconds in `millis()`. Replaced by a small `MicrosecondClock`, with `ClockConfigTest`.
- **Console errors did not say which field was wrong**: invalid JSON in query, headers or body showed only the browser's parser message. Now "Query is not valid JSON (…)" etc.

### 3.3 Code quality (static analysis)

- 7 possible null dereferences (SpotBugs `NP_NULL_ON_SOME_PATH_FROM_RETURN_VALUE`): a nullable getter checked, then called again (`getPrincipal()`, `getType()`, `getInstance()`, `getProperties()`, `getAuthentication()`) in `AuthService`, `AuthController`, `AbsoluteSessionTimeoutFilter`, `ProblemResponseWriter`, `ApiExceptionHandler`. Rewritten with locals and pattern matching; a missing principal now fails clearly instead of with an NPE.
- `ResponseCatalog`: an `instanceof` used as a null check made explicit.
- Entity getters returned the persisted maps (`Environment.getAuthConfig/getDefaultHeaders`, `TestCase.getRequestTemplate/getExpectations`); they now return unmodifiable views.
- `FixedWindowRateLimiter` made `final`.

### 3.4 Dead code and dependencies

- Removed 4 unused test starters (`actuator-test`, `data-jpa-test`, `flyway-test`, `validation-test`); the full suite passes without them.
- Removed 10 unused TypeScript type aliases.

### 3.5 Misleading documentation

- TESTING.md described a planned setup that never existed (WireMock, Mockito, `@DataJpaTest`, a stub `ChatModel`, an `ollama` test tag, a `demo-target` app, CI end-to-end tests, test classes under other names). Rewritten to describe the real suites, fixtures and gates.
- ARCHITECTURE.md: "later: contract validation findings" (built in Phase 6), a route that does not exist, an Ollama health indicator that does not exist, a repository layout with a `demo-target/` module and frontend Dockerfile. Corrected.
- DEPLOYMENT.md: "AI variables are not read yet", wrong AI variable names and defaults, a run command for the non-existent `demo-target`, the full-stack Compose topology presented as arriving "in Phase 8", two trusted-target variables that nothing reads, three variables that are read but were undocumented. Corrected (and `.env.example` completed); the Compose topology is marked as planned.
- SECURITY.md trusted-target example and ADR-018 updated to the actual sample server.
- No `TODO`, `FIXME` or placeholder text in the code (searched).

### 3.6 Test infrastructure

- The new e2e suite first registered its shared account under one e-mail and signed in under another (the run id was computed separately in the setup process and the worker). Fixed; found by the first e2e run.

## 4. Security checks completed

### 4.1 Automated tests (all part of #1 and #9/#10)

| Control | Evidence |
|---|---|
| Authentication: hashing, lockout, session rotation, logout invalidation, password change ending other sessions, account deletion | `AuthIntegrationTest`, `AccountIntegrationTest`, `UserLockoutTest`, `PasswordPolicyTest`, `AbsoluteSessionTimeoutFilterTest`; e2e `auth.spec.ts` |
| Authorization: foreign resources answer like missing ones, no side effects | `CrossUserAccessIntegrationTest`, `EnvironmentRulesIntegrationTest`, `ExecutionRulesIntegrationTest`, `DashboardIntegrationTest` |
| CSRF | `AuthIntegrationTest`, `AccountIntegrationTest`, `ApiExceptionHandlerTest` |
| Input handling never yields 500 or stack traces | `ApiContractIntegrationTest`, `SpecificationEdgeCasesIntegrationTest` |
| SSRF (all blocked ranges, encodings, DNS rebinding, redirects, trusted targets) | `AddressClassifierTest` (54), `UrlGuardTest` (49), `DestinationPolicyTest` (44), `OutboundHttpClientTest` (21), `ExecutionIntegrationTest`, `ExecutionRulesIntegrationTest`; e2e `safety.spec.ts` |
| Secrets: encrypted at rest, write-only, redacted in history, absent from logs and AI prompts | `SecretCipherTest`, `RedactorAndEvaluatorTest`, `EnvironmentIntegrationTest`, `SecretLoggingIntegrationTest`, `AiRulesTest`; e2e `contract-testing.spec.ts`, `safety.spec.ts` |
| AI output validation and failure handling (malformed output, retries, timeouts, missing model, overload, Ollama down) | `AiRulesTest` (44), `OllamaGatewayTest`, `AiIntegrationTest`; e2e `ai.spec.ts` in both modes |

### 4.2 Scans and reviews

Dependency scans (#7, #11–#13), log review (#17), stored-data review (#18), fake-data audit (#19).

### 4.3 SpotBugs findings kept, with reasons

- `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` (280): records and response views that hold lists or maps (built per request and serialized at once), specification records, and Spring beans sharing injected singletons. The persisted mutable entity state flagged here was fixed (§3.3).
- `VA_FORMAT_STRING_USES_NEWLINE` (3): model prompts and SQL text blocks must contain `\n`; `%n` would insert CRLF on Windows.
- `CT_CONSTRUCTOR_THROW` (3): `SecretCipher`, `SecretsConfiguration` (Spring configuration, proxied) and `CoverageSnapshot` (JPA entity, proxied) cannot be final; they validate their input on construction, and no untrusted code subclasses them.

## 5. Remaining limitations

None of these compromises a critical security or core-functionality requirement.

- **PostgreSQL image `gosu`**: the official `postgres:18.6-alpine` image ships `gosu` built with Go 1.24.6; OSV matches 61 Go advisories (1 medium, 60 without a score) against it. `gosu` only switches from root to the `postgres` user at container start and handles no input; only the upstream image can rebuild it. The database is not published beyond `127.0.0.1` in `docker-compose.yml`.
- **End-to-end tests are not in CI**: they need the whole stack (PostgreSQL, backend, frontend, sample API). They run locally (`e2e/`); adding them to CI belongs with the planned Compose images (DEPLOYMENT.md §2).
- **Browsers**: e2e and accessibility checks ran in Microsoft Edge (Chromium) only.
- **Version overrides** (Tomcat, Jackson) must be removed when a Spring Boot release manages these or newer versions (comment in `pom.xml`).
- Earlier limitations still apply (single instance with in-memory sessions and rate limits, no e-mail based password reset, dark mode not checked with axe); see PROJECT_STATUS.md.

## 6. Tests and checks that were not run, and why

| Check | Reason |
|---|---|
| GitHub Actions CI on this branch | Cannot be triggered from this environment (not pushed); the user checks CI |
| E2E in Firefox and WebKit | Browsers not installed; installing them needs about 1 GB on the nearly full system drive |
| OWASP Dependency-Check (NVD) | Replaced by OSV-Scanner, which needs no NVD API key or local database; OSV includes the GitHub advisories found here |
| SpotBugs and OSV-Scanner in the build or CI | Run once for this pass; adding them to CI is not verified here |
| Load and performance tests | Out of scope for this pass; no performance target is defined |
| Real-model AI tests inside `mvnw verify` | By design the build uses the scripted fake; the real model ran in the e2e suite (#9) and in the Phase 8 evaluation |
| Container images of the application | None exist yet (planned for the hardening phase) |
