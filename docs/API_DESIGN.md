# API Design

> Status: conventions, error format, system endpoints and Actuator (Phase 1), authentication, projects and read-only execution history (Phase 2), and specification import and exploration (Phase 3) are **implemented** (marked ✅). Every other endpoint is still a plan, marked with the phase that delivers it.

## 1. Conventions

- Base path: `/api/v1`. JSON only (`application/json`), except spec upload (`multipart/form-data`) and raw spec download.
- **Authentication**: server-side session cookie (`APIFORGE_SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` in prod). All endpoints require authentication except those marked *public*.
- **CSRF**: required on every state-changing request. The SPA reads the `XSRF-TOKEN` cookie and sends `X-XSRF-TOKEN` (Spring Security SPA pattern).
- **Authorization**: resources under `/projects/{projectId}` are only visible to the project owner. A non-owner receives `404 Not Found` (not `403`), so project IDs cannot be enumerated.
- **IDs**: UUID strings.
- **Pagination**: `?page=0&size=20&sort=field,desc`, with `size ≤ 100`. Response: `{ "content": [...], "page": { "number", "size", "totalElements", "totalPages" } }`.
- **Errors**: RFC 9457 `application/problem+json`:

```json
{
  "title": "Bad Request",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "code": "VALIDATION_ERROR",
  "instance": "/api/v1/projects",
  "requestId": "4f1c…",
  "errors": [{ "field": "name", "message": "must not be blank" }]
}
```

- `type` is omitted, which RFC 9457 defines as `about:blank`, so `title` is the HTTP reason phrase. Clients branch on **`code`**, the stable machine-readable identifier (implemented in `ErrorCode.java`), never on `title` or `detail`.
- `requestId` equals the `X-Request-Id` response header. Every response carries this header. A client-supplied `X-Request-Id` matching `^[A-Za-z0-9._-]{8,64}$` is reused; anything else is replaced with a UUID.
- `errors` is present only for input-validation failures.
- The same shape is used for errors raised outside Spring MVC (container `/error` dispatch). Stack traces, exception messages and parser details are never returned.

| Status | `code` examples |
|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` (unreadable JSON, unknown property, wrong type) |
| 401 | `UNAUTHENTICATED` (no or expired session), `BAD_CREDENTIALS` (sign-in failed for any reason, including a locked account) |
| 403 | `CSRF_INVALID`, `REGISTRATION_DISABLED`, `FORBIDDEN`; later `EXECUTION_POLICY_DENIED`, `TEST_NOT_APPROVED` |
| 404 | `NOT_FOUND` |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` |
| 500 | `INTERNAL_ERROR` (details logged server-side under the `requestId`) |
| 409 | `CONFLICT` (generic integrity conflict), `STALE_VERSION` (optimistic lock), `DUPLICATE_NAME`, `EMAIL_TAKEN` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `SPEC_INVALID` (with `messages[]`), later `DESTINATION_BLOCKED` |
| 429 | `RATE_LIMITED` (with `Retry-After`), later `AI_BUSY` |
| 503 | `AI_UNAVAILABLE` (only where no fallback applies) |

- Request DTOs are validated with Bean Validation. Unknown JSON properties are rejected (`FAIL_ON_UNKNOWN_PROPERTIES`).
- Secret values never appear in any response.

- **CORS**: only `/api/**`, only for origins listed in `APIFORGE_CORS_ALLOWED_ORIGINS` (explicit `http(s)://host[:port]`; wildcards fail startup). Credentials are allowed, and `X-Request-Id` is exposed. Same-origin use through the dev proxy or nginx needs no CORS.

## 2. Endpoint inventory

### 2.0 System — Phase 1 ✅ implemented

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/system/info` | *public*. `{name, version, apiVersion, serverTime}`. `version` comes from Maven build metadata, or `"unknown"` if absent |

### 2.1 Auth — Phase 2 ✅ implemented

Paths below are relative to `/api/v1`.

| Method | Path | Description |
|---|---|---|
| GET | `/auth/csrf` | *public*. 204; the response sets the `XSRF-TOKEN` cookie (Spring Security also refreshes it on every response) |
| POST | `/auth/register` | *public*. `{email, password, displayName}` → 201 `{id, email, displayName}` and a signed-in session. 403 `REGISTRATION_DISABLED` when `APIFORGE_AUTH_REGISTRATION_ENABLED=false`; 409 `EMAIL_TAKEN`; 400 with `errors[]` for invalid fields or a password rejected by policy |
| POST | `/auth/login` | *public*. `{email, password}` → 200 user. New session id and new CSRF token on success. Every failure is 401 `BAD_CREDENTIALS` with the same message (unknown e-mail, wrong password, locked account) |
| POST | `/auth/logout` | 204. Invalidates the server session, clears the session cookie and rotates the CSRF token. Requires the CSRF header |
| GET | `/auth/me` | Current user `{id, email, displayName}`; 401 `UNAUTHENTICATED` without a session |

Rules (details in [SECURITY.md](SECURITY.md) §3): passwords 12–64 characters and ≤ 72 bytes, not in the common-password list, not containing the e-mail address; e-mail normalized to lower case; 5 consecutive failures lock sign-in for 15 minutes; per-IP limits of 10 sign-ins and 5 registrations per minute (429 `RATE_LIMITED` + `Retry-After`); sessions expire after 30 minutes idle and 12 hours absolute.

Planned (not implemented): `PATCH /users/me` (display name) and `POST /users/me/password`.

### 2.2 Projects — Phase 2 ✅ implemented

| Method | Path | Description |
|---|---|---|
| GET | `/projects` | Own projects only, paged. `?archived=false\|true` (default `false`), `?q=` case-insensitive name search (`%` and `_` are literal), `?sort=name\|createdAt\|updatedAt,asc\|desc` (default `updatedAt,desc`; other properties → 400), `?page=`, `?size=` (default 20, capped at 100) |
| POST | `/projects` | `{name, description?}` → 201 + `Location`. Name 1–100 characters, trimmed, no control characters; description ≤ 2000. 409 `DUPLICATE_NAME` if an active project of yours has the same name (case-insensitive) |
| GET | `/projects/{projectId}` | `{id, name, description, archived, archivedAt, activeSpecificationVersionId, createdAt, updatedAt, version}` |
| PATCH | `/projects/{projectId}` | `{version, name?, description?, archived?}`. `version` is required: a mismatch gives 409 `STALE_VERSION`. An empty description clears it. Restoring an archived project whose name is now taken gives 409 `DUPLICATE_NAME` |
| DELETE | `/projects/{projectId}` | 204. Permanently deletes the project and, through database cascades, all its specifications, environments, tests and history (the UI requires typing the name) |

A project owned by someone else returns exactly the same 404 as a non-existent one; the attempt is recorded as a `CROSS_USER_ACCESS_DENIED` audit event.

### 2.3 Specifications and operations — Phase 3 ✅ implemented

Base: `/projects/{projectId}/specs`. Every call requires owning the project (others get 404).

| Method | Path | Description |
|---|---|---|
| POST | `` (multipart, part `file`) | Import an uploaded file as a new immutable version. `?activate=true` (default; the first version is always activated) → 201 `{version, messages}` + `Location`. 422 `SPEC_INVALID` with `messages[]` when the document is rejected; 413 `PAYLOAD_TOO_LARGE` above 5 MB |
| POST | `` (JSON `{content, sourceName?}`) | Same, for pasted text |
| POST | `/validate` (multipart or JSON) | Dry run: always 200 `{valid, openapiVersion, title, apiVersion, operationCount, messages}`; persists nothing |
| GET | `` | Paged versions, newest first: number, title, versions, format, source name, operation and warning counts, active flag, SHA-256, size, import time |
| GET | `/{versionId}` | `{summary, description, servers, securitySchemes, tags, messages}` |
| POST | `/{versionId}/activate` | Make a version active; tests whose operation is missing become `STALE`, stale tests whose operation is back become `ACTIVE` |
| GET | `/{versionId}/raw` | The original document byte for byte, `text/plain; charset=UTF-8`, `Content-Disposition: attachment; filename="specification-v{n}.{yaml\|json}"` |
| GET | `/{versionId}/schemas` | Component schemas (`components.schemas`), used to resolve `$ref`s in operation schemas |
| GET | `/{versionId}/operations` | All operations in document order: `{id, operationKey, method, path, operationId, summary, tags, deprecated, warningCount}`. Not paged: the import limit (2,000 operations) bounds it, and the explorer needs the full index for client-side search |
| GET | `/{versionId}/operations/{operationId}` | `{…summary fields, description, parameters, requestBody, responses, security, warnings}` |

**Import messages** (in `messages[]`, persisted with the version): `{severity: ERROR|WARNING|INFO, code, message, location?}`. Any ERROR rejects the document. Codes: `EMPTY_DOCUMENT`, `INVALID_ENCODING`, `PARSE_ERROR`, `DUPLICATE_KEY`, `LIMIT_EXCEEDED`, `NOT_AN_OBJECT`, `UNSUPPORTED_VERSION`, `MISSING_FIELD`, `INVALID_FIELD`, `UNEXPECTED_FIELD`, `UNDECLARED_PATH_PARAMETER`, `EXTERNAL_REFERENCE`, `UNRESOLVED_REFERENCE`, `INVALID_REFERENCE`, `DUPLICATE_OPERATION_ID`, `AMBIGUOUS_PATH`, `UNSUPPORTED_FEATURE`, `NO_OPERATIONS`, `NO_SERVERS`, `IDENTICAL_CONTENT`, `PARSER_NOTICE`. `location` is a line/column for syntax errors and a document path (e.g. `paths["/pets/{id}"].get`) otherwise.

**Normalized operation content**: parameters are the effective set (path-level merged with operation-level, the operation wins on name + location) with `$ref`s to `components.parameters` resolved; request bodies, responses and response headers likewise; responses are ordered numerically with `default` last; `security` is the effective requirement list (an empty list means "no authentication"). Schema `$ref`s are kept and resolved against `/schemas`, which keeps recursive schemas finite.

### 2.4 Environments ✅ Phase 5

| Method | Path | Description |
|---|---|---|
| GET | `/projects/{projectId}/environments` | List with variables (secret values never included) |
| POST | `/projects/{projectId}/environments` | `{name, baseUrl, timeoutMs, followRedirects, executionPolicy, authorizationConfirmed, auth, defaultHeaders}` → 201. `baseUrl` is parsed strictly (http/https, no credentials, no query, canonical IPs only) and checked against the destination policy without DNS (blocked names, literal addresses, ports); DNS is checked again at every execution. `executionPolicy` other than `READ_ONLY` requires `authorizationConfirmed: true`. `auth = {type: NONE \| BEARER \| BASIC \| API_KEY_HEADER \| API_KEY_QUERY, tokenVariable?, usernameVariable?, passwordVariable?, headerName?, parameterName?, valueVariable?}` names variables, never values. `defaultHeaders` follow the test header rules (no transport, Content-Type or credential headers; `{{VAR}}` allowed) |
| GET | `/projects/{projectId}/environments/{envId}` | Detail; variables as `{name, secret, value, hasValue}` with `value: null` for secrets |
| PUT | `/projects/{projectId}/environments/{envId}` | Same body plus `version` (optimistic locking, 409 `STALE_VERSION`); names unique per project (409 `DUPLICATE_NAME`) |
| DELETE | `/projects/{projectId}/environments/{envId}` | 204; runs keep their environment name and base URL snapshots |
| PUT | `/projects/{projectId}/environments/{envId}/variables/{name}` | `{value, secret}` upsert; secrets are encrypted (AES-256-GCM, bound to environment and name) and write-only; values ≤ 4096 characters without line breaks; audited as `SECRET_CHANGED` (name only) |
| DELETE | `/projects/{projectId}/environments/{envId}/variables/{name}` | 204 |

### 2.5a Execution history (read-only) — Phase 2 ✅ implemented

| Method | Path | Description |
|---|---|---|
| GET | `/projects/{projectId}/runs` | Paged runs, newest first (`?sort=createdAt\|startedAt\|finishedAt\|status`). Each run: status, environment name/base URL snapshots, outcome counts, timestamps |
| GET | `/projects/{projectId}/runs/{runId}` | One run |
| GET | `/projects/{projectId}/runs/{runId}/results` | Paged result summaries (`?outcome=PASSED\|FAILED\|ERROR\|SKIPPED\|INCONCLUSIVE`, `?sort=executedAt\|outcome\|durationMs`, default `executedAt,asc`). Each summary includes `failedChecks`, `skippedChecks` and `firstFailure` (Phase 6) |
| GET | `/projects/{projectId}/runs/{runId}/results/{resultId}` | Full result: request snapshot, response, checks (assertions, §2.6), error kind and message, `durationMs` and `timeToHeadersMs` |
| GET | `/projects/{projectId}/runs/{runId}/comparison?baseline=` | Outcome changes against `baseline`, or by default against the previous run of the same kind (404 when there is none): `{baseline, current, counts: {FIXED, REGRESSED, CHANGED, UNCHANGED, NEW, REMOVED}, changes: [{testName, operationKey, testCaseId, baselineOutcome, currentOutcome, kind, baselineResultId, currentResultId}]}` (Phase 6) |

Runs and results are always resolved through the project and run in the path, so an id from another project returns 404. Runs are created by the execution engine (§2.5).

### 2.5 Execution ✅ Phase 5

| Method | Path | Description |
|---|---|---|
| POST | `/projects/{projectId}/runs` | `{environmentId, testIds?: [], operationKey?, suiteId?}` → **202** with the run (`QUEUED`) and a `Location`. Without `testIds` every runnable test (enabled, approved, active) is selected, optionally narrowed to an operation or suite; explicitly selected tests must all be runnable (else 400). At most 500 tests. Tests above the environment's execution policy are recorded as `SKIPPED` with the reason, never sent. More than 2 concurrent executions per user → 429 `RATE_LIMITED`. Audited (`RUN_STARTED`) |
| POST | `/projects/{projectId}/runs/{runId}/cancel` | 202; aborts the request in flight (`ERROR/CANCELLED`) and records the remaining tests as `SKIPPED`; the run ends `CANCELLED`. A finished run → 409 |
| POST | `/projects/{projectId}/executions` | `{environmentId, method, path, request: {query, headers, body, auth}, expectedStatuses?}` (path concrete, no query/fragment; `expectedStatuses` default `["2XX"]`). Synchronous; stored as a one-result `AD_HOC` run; returns the result detail. Method above the environment policy → 403 `EXECUTION_POLICY`; request budget (300/min per user) exhausted → 429 |
| GET | `/projects/{projectId}/runs`, `/runs/{runId}`, `/runs/{runId}/results[/{resultId}]` | History (§2.5a). Runs include `kind` (`TEST_RUN`, `AD_HOC`) and `statusReason`; results include `correlationId`, the redacted request snapshot (method, URL, headers, body, auth mode, redirects followed), the redacted response (status, headers, text body up to 256 KB, size, truncation, content type), checks and the error kind |

**Outcomes.** A response is evaluated with `STATUS` (against the test's expected codes or classes) plus the contract assertions of §2.6. Any `FAIL` → `FAILED`; otherwise any `INCONCLUSIVE` → `INCONCLUSIVE`; otherwise `PASSED`. `SKIPPED` assertions never count as passes. (Results recorded in Phase 5 may still contain `CONTENT_TYPE`/`RESPONSE_SCHEMA` checks with result `NOT_EVALUATED`.) A request that got no usable response is `ERROR` with `TIMEOUT`, `CONNECT_FAILED`, `DNS_FAILURE`, `TLS_ERROR`, `BLOCKED_BY_POLICY`, `REDIRECT_BLOCKED`, `INVALID_RESPONSE`, `VARIABLE_UNRESOLVED`, `CANCELLED` or `INTERNAL`.

**Correlation.** Every request carries `X-APIForge-Request-Id` (a dedicated name, so it never collides with headers the API under test documents); the same id is stored with the result and appears in APIForge's outbound log line.

### 2.6 Contract validation ✅ Phase 6

Every response is validated against the operation of the **active** specification that matches the request (method and path template). The results are the `checks` of the result detail, in this order:

| Check | Result |
|---|---|
| `STATUS` | `PASS`/`FAIL`: the status is one of the test's expected statuses |
| `STATUS_DOCUMENTED` | `PASS` (exact code, class such as `4XX`, or `default`); `FAIL` with the documented statuses as `expected`; `SKIPPED` when no operation matches |
| `CONTENT_TYPE` | `PASS`; `FAIL` when a body has no or an undocumented media type; `SKIPPED` when the response is documented without a body |
| `JSON_SYNTAX` | Any body declaring JSON: `PASS`, `FAIL` (line and column, never body text), `INCONCLUSIVE` when truncated |
| `BODY_SCHEMA` | `PASS`; one `FAIL` per violation; `INCONCLUSIVE` (truncated, non-JSON with a schema, unsupported keywords); `SKIPPED` with the reason (no documented body or schema, undocumented status, content-type mismatch, invalid JSON, validation turned off) |

Assertion shape (fields other than `name`, `result` and `message` appear when they apply):

```json
{
  "name": "BODY_SCHEMA",
  "result": "FAIL",
  "path": "$.items[0].price",
  "keyword": "type",
  "expected": "number",
  "actual": "string \"19.90\"",
  "message": "$.items[0].price expected number but found string."
}
```

Values in `expected`, `actual` and `message` are redacted like the rest of the result. An aggregated per-rule report endpoint was planned here but is not implemented; findings are reported per result.

### 2.7 Test cases, suites and generation ✅ Phase 4

The brief moved deterministic generation ahead of execution, so this section is implemented in Phase 4 (ADR-025). Tests are listed, edited and organized here; they are executed by the runs API (§2.5) once execution exists.

| Method | Path | Description |
|---|---|---|
| POST | `/projects/{projectId}/tests/generate` | Body `{operationKeys?: []}` (omitted or empty = every operation of the **active** version; unknown keys → 400). Idempotent through the scenario identity → `{specificationVersionId, specificationVersionNumber, operations, created, updated, unchanged, keptUserEdits, archived, total, generatorVersion, skipped: [{operationKey, scenario, reason}]}`. No active version → 409 `NO_ACTIVE_SPECIFICATION`. Audited (`TESTS_GENERATED`) |
| GET | `/projects/{projectId}/tests/generation-plan?operationKey=` | What generation produces for one operation, without storing anything: `{scenarios: [{scenarioKey, name, category, expectedStatuses}], skipped: [...]}`. The UI uses it to explain what is *not* generated |
| GET | `/projects/{projectId}/tests` | Paged (default 50, max 100). Filters `operationKey, suiteId, category, origin, enabled, reviewStatus, lifecycleStatus` (archived only when asked for), `q` (name contains). Sort allow-list `name, operationKey, category, sortOrder, updatedAt, createdAt`; default `operationKey, sortOrder, name` |
| GET | `/projects/{projectId}/tests/summary` | `{total, enabled, stale, operations: [{operationKey, total, enabled, stale}]}` |
| GET | `/projects/{projectId}/tests/{testId}` | Detail (shape below); archived tests → 404 |
| PUT | `/projects/{projectId}/tests/{testId}` | `{name, request, expectations: {statuses, summary?, validateResponseSchema?}, rationale?, version}`. Validated (path parameters must match the path; no `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, `Content-Type`, `Authorization`, `Cookie`, `Proxy-*` headers; no CR/LF; body ≤ 256 KB; statuses `NNN` or `NXX`) → 400 `VALIDATION_ERROR` with per-field `errors`. Marks the test `userModified` (regeneration leaves it alone); an edited AI suggestion returns to `PENDING_REVIEW`. Stale `version` → 409 |
| PATCH | `/projects/{projectId}/tests/{testId}` | `{enabled?, suiteId?, version}`: enable/disable or move |
| POST | `/projects/{projectId}/tests/bulk` | `{testIds (1–500), enabled?, suiteId?}` → `{updated}`; any id outside the project → 404 and nothing changes |
| POST | `/projects/{projectId}/tests/{testId}/review` | `{decision: "APPROVE" \| "REJECT"}`; rejected tests never run |
| GET, POST | `/projects/{projectId}/test-suites` | Suites with `testCount`; create a CUSTOM suite `{name, description?}` (names unique per project, case-insensitive → 409 `DUPLICATE_NAME`) |
| PATCH, DELETE | `/projects/{projectId}/test-suites/{suiteId}` | Rename `{name?, description?, version}`; delete only an **empty custom** suite (otherwise 409), so deleting a suite never loses tests |

Tests are not hard-deleted through the API: disable a test to stop it from running; regeneration archives generated tests whose scenario no longer applies.

### 2.8 Coverage ✅ Phase 7

| Method | Path | Description |
|---|---|---|
| GET | `/projects/{projectId}/coverage?environmentId=` | Coverage of the active version: `version`, `metrics` (`{id, group: TEST_DESIGN\|EXECUTION, label, value, total?, definition}`), `outcomes` of distinct enabled tests (`tests, executed, passed, failed, error, inconclusive, skipped, notRun`), one row per operation (tests, duplicates, inactive tests, responses targeted/exercised, constraints, branches, missing negative kinds, undocumented statuses received, outcomes) and `gaps` (`operationsWithoutTests`, `responsesNotExercised[{operationKey, status, targeted}]`, `missingNegatives[{kind, label, operations}]`). `environmentId` limits execution metrics to runs in that environment. No active version → 409 `NO_ACTIVE_SPECIFICATION` |
| GET | `/projects/{projectId}/coverage/operation?key=&environmentId=` | Drill-down of one operation (`key` = `METHOD /path`): responses with received statuses, negative kinds (`PRESENT`, `MISSING`, `NOT_APPLICABLE`), planned scenarios with covered state and test ids, scenarios the generator skips with the reason, schema branches, enabled tests with `duplicateOf` and latest outcome. Unknown key → 404 |
| GET | `/projects/{projectId}/coverage/changes?from=` | Changes from `from` (default: the version numbered just below the active one) to the active version: operations `ADDED`/`REMOVED`/`CHANGED` with aspects, tests, tests generated from an earlier version and stale tests; `unchanged` count; `baseline` (latest snapshot of `from`) and `current` summary numbers; `history` (latest 50 snapshots) |

Metric definitions are part of the response and are documented in ADR-028. A cross-project dashboard endpoint was planned here and is not implemented.

### 2.9 AI ✅ Phase 8

| Method | Path | Description |
|---|---|---|
| GET | `/ai/status?refresh=` | `{enabled, state: READY\|MODEL_MISSING\|UNAVAILABLE\|DISABLED, model, server, installedModels[], message, latencyMs, checkedAt}`; cached 10 s, `refresh=true` checks now |
| POST | `/projects/{projectId}/ai/suggestions` | `{operationKey}` → `{operationKey, model, latencyMs, attempts, created: [test detail…], discarded: [{name, reason}], possibleDuplicates: [{name, similarTo, difference}]}`. Created tests are `AI_SUGGESTED`, `PENDING_REVIEW`, not executable; accept/reject with `POST /tests/{id}/review`, edit with `PUT /tests/{id}` |
| POST | `/projects/{projectId}/runs/{runId}/results/{resultId}/explanation` | Explains a FAILED, ERROR or INCONCLUSIVE result (others → 409): `{id, resultId, model, latencyMs, createdAt, summary, observedFacts: [{statement, source, grounding}], likelyCauses: [{hypothesis, confidence, evidence: [{text, grounding}]}], debuggingSteps[], suggestedChanges: [{target: TEST\|SPECIFICATION\|API_IMPLEMENTATION, change}], inputs[], sourceCodeProvided: false, warnings[]}` |
| GET | same path | The latest stored explanation, or 404 |

AI errors: `AI_UNAVAILABLE` (503: disabled, not reachable or other Ollama error), `AI_MODEL_NOT_FOUND` (503), `AI_BUSY` (503), `AI_TIMEOUT` (504), `AI_INVALID_OUTPUT` (502), `PAYLOAD_TOO_LARGE` (413, prompt over the limit), `RATE_LIMITED` (429 with `retryAfterSeconds`). Each problem carries `aiFailure` with the classified kind. No other endpoint depends on AI.

### 2.10 Operational endpoints (Actuator)

| Path | Exposure |
|---|---|
| `/actuator/health`, `/actuator/health/liveness`, `/actuator/health/readiness` | *public*. Component statuses are shown, but component details never are. Readiness = `readinessState` + `db`; liveness = `livenessState` only, so a database outage does not trigger restarts. Returns `503` when DOWN ✅ Phase 1 |
| `/actuator/info` | *public* (build name, group, version) ✅ Phase 1 |
| All other actuator endpoints | Not exposed |

## 3. Key DTO sketches

```jsonc
// Operation detail (excerpt)
{
  "id": "…", "operationKey": "POST /pets", "method": "POST", "path": "/pets",
  "operationId": "createPet", "summary": "…", "tags": ["pets"], "deprecated": false,
  "security": [{ "scheme": "bearerAuth", "type": "http", "scopes": [] }],
  "parameters": [{ "name": "X-Request-Id", "in": "header", "required": false, "schema": { … } }],
  "requestBody": { "required": true, "content": { "application/json": { "schema": { … } } } },
  "responses": [{ "status": "201", "description": "…", "content": { "application/json": { "schema": { … } } } }],
  "testSummary": { "total": 7, "approved": 6, "pendingReview": 1 }
}

// Test case (GET /tests/{id})
{
  "id": "…", "suiteId": "…", "operationKey": "POST /products", "method": "POST", "path": "/products",
  "name": "Missing required field 'name'", "category": "MISSING_FIELD", "origin": "DETERMINISTIC",
  "reviewStatus": "APPROVED", "lifecycleStatus": "ACTIVE", "riskLevel": "MUTATING",
  "enabled": true, "userModified": false, "executable": true,
  "scenarioKey": "body.missing:/name", "specLocation": "#/components/schemas/NewProduct/required",
  "rationale": "'name' is listed in 'required' of schema NewProduct. The request omits it.",
  "generatorVersion": "1", "sourceSpecificationVersionId": "…",
  "request": { "pathParams": {}, "query": {}, "headers": {},
               "body": { "mode": "JSON", "contentType": "application/json", "value": { "sku": "HJB-7247", "price": 129.99, "category": "tools" } },
               "auth": "ENVIRONMENT" },                       // ENVIRONMENT | NONE | INVALID
  "expectations": { "statuses": ["400", "422"],              // exact codes or classes such as "4XX"
                    "summary": "Rejected with 400 or 422, the documented client-error responses for invalid input. …",
                    "validateResponseSchema": true,
                    "responseChecks": [{ "status": "400", "mediaType": "application/json", "schemaName": "Error" }] },
  "version": 0
}

// Coverage metric
{ "key": "operationsTested", "label": "Operations with ≥1 executed test",
  "numerator": 14, "denominator": 20, "ratio": 0.70,
  "definition": "Operations in the selected spec version that have at least one execution (any outcome except SKIPPED) in the selected window." }
```

## 4. Versioning and compatibility

- Breaking changes require `/api/v2`. Additive fields are allowed in v1.
- The frontend's TypeScript types are maintained alongside zod schemas that validate responses at runtime in development builds.
