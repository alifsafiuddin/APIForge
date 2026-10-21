# Project Status

**Current phase**: Phase 5 — Secure API execution engine — **complete** (2026-10-21)
**Next phase**: decided by the next phase brief (planned: contract validation of responses)
**Runnable today**: sign-in and private projects; OpenAPI import and explorer; deterministic test generation and curation; **environments with encrypted secrets, test runs and console requests through a guarded outbound client**; run history with redacted requests and responses. Not yet: response schema (contract) validation, coverage, AI.

## Phase 5 summary

### Delivered

| Requirement | Implementation | Where |
|---|---|---|
| GET, POST, PUT, PATCH, DELETE with path, query, headers, bodies and configured authentication | Requests built from tests or console input: encoded path and query values, default + test headers, JSON or raw bodies, bearer / basic / API key (header or query), plus deliberately missing or invalid credentials for authentication tests | `execution/service/RequestFactory` |
| Environment variables and reusable request configuration | Environments: base URL, read timeout, redirects, execution policy, authentication, default headers, variables (`{{NAME}}` in path values, query, headers, bodies); secrets AES-256-GCM encrypted and write-only | `environment/*`, `secrets/*`, V9 |
| Timeouts with safe upper bounds; request and response size limits | Connect 5 s (≤ 30 s), read per environment (≤ 60 s), total deadline 30 s (≤ 3 min) incl. redirects; request ≤ 1 MB; response read ≤ 2 MB then aborted and marked truncated; ≤ 256 KB text stored; binary not stored | `OutboundProperties`, `OutboundHttpClient` |
| Record status, headers, safe body, duration | Every result: status, redacted headers, text body, size, truncation, content type, duration, checks, correlation id | `RunExecutor`, `TestResult` |
| Network errors, timeouts, malformed JSON, non-2xx | Classified errors (`TIMEOUT`, `CONNECT_FAILED`, `DNS_FAILURE`, `TLS_ERROR`, `INVALID_RESPONSE`, …); non-2xx are responses evaluated against expectations; JSON syntax and content-type checks | `OutboundHttpClient.classify`, `OutcomeEvaluator` |
| Cancellation and timeout outcomes | Cancel aborts the request in flight (`CANCELLED`) and skips the rest (run `CANCELLED` with reason); runs left by a restart become `INTERRUPTED` | `Cancellation`, `RunExecutor`, `RunRecorder` |
| Persist runs and results | Runs (`TEST_RUN`, `AD_HOC`) with counts and status reason; immutable results written one by one | V9, `RunRecorder` |
| Secrets never exposed | Write-only API; redaction (raw, URL-encoded, Base64, Basic token, sensitive headers, API-key query parameters) before anything is stored, returned or logged; credentials redacted even from plain variables; logs carry no query strings, headers or bodies | `Redactor`, `EnvironmentViews` |
| Correlation ids | `X-APIForge-Request-Id` on every request, stored with the result and in the outbound log line (MDC) | `RequestFactory`, `RunExecutor` |
| Maintained client with pooling and limits | Apache HttpClient 5.6.4, pooled (64/8 per route, TTL), header limits, no proxy, no automatic redirects/retries/cookies/decompression | `OutboundHttpClient` |
| Untrusted URLs; explicit trusted-target policy; private, loopback, link-local, multicast, metadata blocked | `UrlGuard` (strict parser, canonical IPs only), `AddressClassifier` (incl. IPv4 inside IPv6), `DestinationPolicy` (name blocks, any-blocked-answer rule, ports); trusted targets exact host:port, dev only, never link-local/metadata, refused with prod unless acknowledged | `outbound/*` (ADR-026) |
| Resolve before connecting; rebinding; actual connection checked | One validated lookup; the client's resolver returns only those addresses; peer classified after connecting | `GuardedDnsResolver`, `DestinationPolicy.requireAllowedPeer` |
| Redirects revalidated; not followed to untrusted hosts | Manual redirects only when enabled, each hop parsed and checked, credentials dropped across origins, ≤ 5 hops, `REDIRECT_BLOCKED` otherwise | `OutboundHttpClient.execute` |
| Alternative IP formats, parsing tricks, unsupported protocols | Refused by `UrlGuard` (13 notations, user info, backslashes, percent-encoding, zone ids, Unicode hosts, non-http schemes) | `UrlGuardTest` |
| Per-user execution and concurrency limits; not a scanner | 2 concurrent executions and 300 requests/min per user, 500 tests per run, 8 runs globally; no port or host ranges, ports below 1024 except 80/443 refused, generic error messages | `RunExecutor`, `ExecutionService` |
| UI | Environments page (authorization notice, policy confirmation, write-only secrets), Run dialog in the Tests workspace, Runs history and run page (live progress, cancel, result panel), Console | `frontend/src/features/environments`, `features/runs` |
| Authorized sample API | `samples/warehouse-lab-server.mjs` (the Warehouse Lab contract with one documented deviation, plus diagnostic endpoints) | samples/README.md |

### Verification (executed 2026-10-21; Windows 11, Docker Desktop 29.9.1, Node 24.22.0, JDK 21.0.8)

| Check | Method | Result |
|---|---|---|
| Outbound security tests | `AddressClassifierTest`, `UrlGuardTest`, `DestinationPolicyTest`, `OutboundHttpClientTest` | ✅ **168/168** (run before any execution code was built on top, and again in every full build) |
| Backend build and tests (clean checkout) | `git checkout-index` of the staged tree into an empty directory, `mvnw verify` | ✅ **624 tests, 0 failures, 0 errors, 0 skipped**; enforcer rules pass; line coverage 92.1% (4793/5203), branch 77.5%; `outbound` 90.1%, `secrets` 94.9%, `execution` 93.6%, `generation` 91.0%, `environment` 83.4% |
| Frontend (clean checkout) | `npm ci`, `npm run check`, coverage, `npm run build` | ✅ 0 vulnerabilities; 0 lint findings; tsc clean; **85/85 tests**; lines 87.0%, branches 71.5%; build without warnings |
| Migration on existing data | Backend start against the Phase 4 dev database | ✅ V8 → V9 applied |
| **Completion check**: execute against an authorized sample API — success, HTTP errors, timeouts, malformed responses, blocked destinations, redirect restrictions, secret redaction | Headless Microsoft Edge against the running stack (PostgreSQL in Docker, backend jar with the dev profile, Vite) and `samples/warehouse-lab-server.mjs` on 127.0.0.1:8090. Imported and generated the Warehouse Lab tests, created the environment through the UI (secret API key), ran all tests from the Tests workspace, then sent console requests; afterwards scanned the database and the backend log | ✅ **25/25 checks.** Run of all **128 generated tests: 127 passed, 1 failed, 0 errors** — the failure is the sample's documented deviation ("Field 'lines[0].quantity' above maximum 999" → 201, "Expected 400, got 201"). Success (`GET /products/42` 200, JSON checks); HTTP error (503 → FAILED with response); timeout (`/lab/slow` → `TIMEOUT` after the 1 s read timeout); malformed JSON (`JSON_SYNTAX` FAIL); **blocked destinations**: metadata address and `0x7f.0.0.1` refused when saving, `localtest.me` (public DNS → 127.0.0.1) saved but **blocked at execution after resolution**; **redirects**: not followed when disabled (302 recorded), same-origin followed when enabled, to the metadata address and to an untrusted local port (PostgreSQL's) refused; **secret redaction**: the target echoed the key, every page and API response showed «redacted»; secret variable shown write-only; mobile layout; no JavaScript errors |
| Secrets at rest and in logs | `psql` and the backend log after the browser run | ✅ The key appears **0 times** in results (requests, responses, headers, errors), audit events, plaintext variables and the backend log; 6 secret variables stored only encrypted; 2 `OUTBOUND_BLOCKED` audit events; 282 outbound log lines, each with its correlation id |

### Defects found and fixed during this phase

- **Correlation header collision:** the default `X-Request-Id` would override a header that APIs document themselves (the Warehouse sample requires it), so the generated "missing required header" test could not omit it. Renamed to `X-APIForge-Request-Id`.
- **The sample API answered 415 for a missing body** where its contract documents 400: the first completion run reported 2 extra failures, which were real deviations of the sample. Fixed in the sample (body checked before content type); the run then had exactly the one deliberate deviation.
- **`jar:` URLs were reported as invalid instead of unsupported** (opaque check before the scheme check); reordered.
- **Unicode host names** cannot be parsed by `java.net.URI`; they are now refused with a request for the punycode form instead of a second parser.
- A TLS probe against a plain HTTP server times out rather than failing the handshake; the TLS test now uses a server that answers with non-TLS bytes, and the timeout behaviour is accepted as correct.
- Test-side mistakes corrected while the engine behaved correctly: expected checks (JSON syntax is checked for JSON responses), case of header names echoed by the JDK server, run order assumptions in the cancellation test, unordered map in a URL assertion.
- `ApiForgeProperties` was left unchanged (outbound and secrets have their own properties), and the header rules shared by tests, environments and the console moved to `common.http.HeaderRules`.

### Known limitations

- Response bodies are not yet validated against the documented schemas: that check is shown as `NOT_EVALUATED`, and a `PASSED` result states which checks ran (status, content type, JSON syntax).
- Rate limits and run concurrency are per instance, in memory (single-instance deployment, ADR-014).
- The connect timeout is global (configuration); read timeouts are per environment.
- Only JSON and raw text bodies are sent (no multipart or form encoding yet).
- Run progress is polled (1 s), not pushed.
- GitHub CI status for this branch is checked by the user.

## History

- **Phase 4 (2026-10-21)** — Deterministic test generation (V8): validated values, single-fault negatives, explicit expectations, stable scenario identity, idempotent regeneration; test workspace with review, edit, enable/disable and suites. 388 backend and 81 frontend tests; completion check with the Warehouse Lab sample (128 tests, regeneration repeatable). Committed as `58aa5b7`.
- **Phase 3 (2026-10-21)** — OpenAPI 3.0/3.1 import (upload or paste) with strict validation, local-only references, immutable versions and normalized operations (V7); specification explorer. 189 backend and 74 frontend tests; completion check with the official Petstore 3 sample. Committed as `396be8e`.
- **Phase 2 (2026-10-21)** — Database schema (V2–V6), session authentication with CSRF, lockout, rate limiting and audit trail; ownership-based authorization with 404 for foreign resources; projects CRUD; read-only execution history; ArchUnit rules. 129 backend and 51 frontend tests; committed as `0da6635` (branch `phase-2-database-auth`).
- **Phase 1 (2026-10-21)** — Runnable foundation: Spring Boot 4.1.1, PostgreSQL 18.6, Flyway, Actuator health, problem+json errors, request correlation, CORS, React shell, CI workflow. Committed as `fbbd96f`.
- **Phase 0 (2026-10-20)** — Design documents and verified dependency matrix.

## Open risks carried forward

1. **openapi-request-validator message mapping** (JSON path, expected/actual) and **regex DoS in schema `pattern`** inside that library: spike when contract validation starts (ADR-006). The generator's own pattern matching is already budgeted (ADR-025).
2. **Multi-instance deployment** would need shared rate limits and run coordination (in memory today).
3. **Small-model JSON reliability** for AI features (ADR-019).

## Milestone checklist

- [x] Phase 0 — Design
- [x] Phase 1 — Foundation
- [x] Phase 2 — Database design and authentication
- [x] Phase 3 — OpenAPI import and explorer
- [x] Phase 4 — Deterministic test generation
- [x] Phase 5 — Secure execution engine (environments, secrets, outbound safety, runs, console)
- [ ] Contract validation of responses
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
| Contract validation produces explainable results | ☐ |
| Test history and coverage persist | ◐ Runs and results persist (Phase 5); coverage pending |
| AI suggestions and explanations work through a local model | ☐ |
| AI outages do not break deterministic testing | ☐ |
| Auth, authorization and outbound safeguards tested | ✅ incl. 168 outbound security tests and execution ITs |
| Automated tests and CI pass | ◐ Local runs pass; GitHub CI not yet run |
| Docker-based local setup works | ◐ PostgreSQL only |
| README has reproducible setup and usage | ✅ for the Phase 3 scope |
| No unresolved critical defects or falsely advertised features | ✅ |
