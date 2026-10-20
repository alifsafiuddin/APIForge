# API Design

> Status: conventions, error format, system endpoints and Actuator are **implemented (Phase 1)**. Every other endpoint is still a plan, marked with the phase that delivers it.

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
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "One or more fields are invalid.",
  "code": "VALIDATION_ERROR",
  "instance": "/api/v1/projects",
  "requestId": "4f1c…",
  "errors": [{ "field": "name", "message": "must not be blank" }]
}
```

- `type` is `about:blank`, so `title` is the HTTP reason phrase. Clients branch on **`code`**, the stable machine-readable identifier (implemented in `ErrorCode.java`), never on `title` or `detail`.
- `requestId` equals the `X-Request-Id` response header. Every response carries this header. A client-supplied `X-Request-Id` matching `^[A-Za-z0-9._-]{8,64}$` is reused; anything else is replaced with a UUID.
- `errors` is present only for input-validation failures.
- The same shape is used for errors raised outside Spring MVC (container `/error` dispatch). Stack traces, exception messages and parser details are never returned.

| Status | `code` examples |
|---|---|
| 400 | `VALIDATION_ERROR`, `MALFORMED_REQUEST` (unreadable JSON, unknown property, wrong type) |
| 401 | `UNAUTHENTICATED`, `BAD_CREDENTIALS` |
| 403 | `CSRF_INVALID`, `EXECUTION_POLICY_DENIED`, `TEST_NOT_APPROVED` |
| 404 | `NOT_FOUND` |
| 405 / 406 / 415 | `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` |
| 500 | `INTERNAL_ERROR` (details logged server-side under the `requestId`) |
| 409 | `CONFLICT`, `STALE_VERSION` (optimistic lock), `DUPLICATE_NAME` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 422 | `SPEC_INVALID`, `SPEC_EXTERNAL_REF`, `DESTINATION_BLOCKED` |
| 423 | `ACCOUNT_LOCKED` |
| 429 | `RATE_LIMITED`, `AI_BUSY` |
| 503 | `AI_UNAVAILABLE` (only where no fallback applies) |

- Request DTOs are validated with Bean Validation. Unknown JSON properties are rejected (`FAIL_ON_UNKNOWN_PROPERTIES`).
- Secret values never appear in any response.

- **CORS**: only `/api/**`, only for origins listed in `APIFORGE_CORS_ALLOWED_ORIGINS` (explicit `http(s)://host[:port]`; wildcards fail startup). Credentials are allowed, and `X-Request-Id` is exposed. Same-origin use through the dev proxy or nginx needs no CORS.

## 2. Endpoint inventory

### 2.0 System — Phase 1 ✅ implemented

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/system/info` | *public*. `{name, version, apiVersion, serverTime}`. `version` comes from Maven build metadata, or `"unknown"` if absent |

### 2.1 Auth and account — Phase 2

| Method | Path | Description |
|---|---|---|
| GET | `/auth/csrf` | *public*. Issues the CSRF cookie |
| POST | `/auth/register` | *public* (if `apiforge.auth.registration-enabled`). `{email, password, displayName}` → 201 |
| POST | `/auth/login` | *public*. `{email, password}` → 200 user; rotates the session id |
| POST | `/auth/logout` | Invalidates the session → 204 |
| GET | `/auth/me` | Current user `{id, email, displayName}` |
| PATCH | `/users/me` | Update `displayName` |
| POST | `/users/me/password` | `{currentPassword, newPassword}` → 204; invalidates other sessions |

### 2.2 Projects — Phase 2

| Method | Path | Description |
|---|---|---|
| GET | `/projects` | Paged list of own projects (`?archived=false`) |
| POST | `/projects` | `{name, description}` → 201 |
| GET | `/projects/{projectId}` | Detail including active spec summary |
| PATCH | `/projects/{projectId}` | `{name?, description?, archived?}` with `version` for optimistic locking |
| DELETE | `/projects/{projectId}` | Hard delete, cascading (UI requires typed confirmation) → 204 |

### 2.3 Specifications and operations — Phase 2

| Method | Path | Description |
|---|---|---|
| POST | `/projects/{projectId}/specs/validate` | Dry-run parse (multipart `file` or JSON `{content}`), returns messages, persists nothing |
| POST | `/projects/{projectId}/specs` | Import a new immutable version. `?activate=true` (default) → 201 `{version, warnings, operationCount, staleTestCount}` |
| GET | `/projects/{projectId}/specs` | List versions (number, title, imported at, hash, operation count, active flag) |
| GET | `/projects/{projectId}/specs/{versionId}` | Version detail: info, servers, security schemes, tags, messages |
| GET | `/projects/{projectId}/specs/{versionId}/raw` | Original content (`text/plain`, `Content-Disposition: attachment`) |
| POST | `/projects/{projectId}/specs/{versionId}/activate` | Make an older version active (re-marks stale tests) |
| GET | `/projects/{projectId}/specs/{versionId}/diff?against={otherId}` | Added, removed and changed operation keys *(Phase 5, if time permits; not required)* |
| GET | `/projects/{projectId}/operations` | `?specVersionId=&tag=&method=&q=` summary list (defaults to active version) |
| GET | `/projects/{projectId}/operations/{operationId}` | Full detail: parameters, request body, responses with schemas, security requirements |

### 2.4 Environments — Phase 3

| Method | Path | Description |
|---|---|---|
| GET | `/projects/{projectId}/environments` | List |
| POST | `/projects/{projectId}/environments` | `{name, baseUrl, timeoutMs, followRedirects, executionPolicy, auth}` — `baseUrl` is syntax-checked and run through the destination policy (no DNS at save time; DNS is checked at execution) |
| GET | `/projects/{projectId}/environments/{envId}` | Detail; variables listed as `{name, secret, value}` where `value` is `null` for secrets and `hasValue: true` is set instead |
| PUT | `/projects/{projectId}/environments/{envId}` | Update |
| DELETE | `/projects/{projectId}/environments/{envId}` | Delete (runs keep their snapshots) |
| PUT | `/projects/{projectId}/environments/{envId}/variables/{name}` | `{value, secret}`. Upsert; a secret value is write-only |
| DELETE | `/projects/{projectId}/environments/{envId}/variables/{name}` | Remove variable |
| POST | `/projects/{projectId}/environments/{envId}/connectivity-check` | Guarded `GET` of the base URL; reports reachability and timing, never the body |

### 2.5 Execution — Phase 3 (ad hoc), Phase 5 (runs of test cases)

| Method | Path | Description |
|---|---|---|
| POST | `/projects/{projectId}/executions/ad-hoc` | `{environmentId, operationKey, request: {pathParams, query, headers, body}}`. Synchronous; creates a one-item run and returns the execution |
| POST | `/projects/{projectId}/runs` | `{environmentId, testCaseIds?: [], operationKeys?: [], includeCategories?: []}` → `202 {runId}`. Rejects unapproved or rejected tests (`TEST_NOT_APPROVED`) and tests above the environment policy |
| GET | `/projects/{projectId}/runs` | Paged history, filterable by `status`, `environmentId`, date |
| GET | `/projects/{projectId}/runs/{runId}` | Run summary + execution list (outcome, duration, finding counts) |
| POST | `/projects/{projectId}/runs/{runId}/cancel` | Cancel remaining executions → 202 |
| GET | `/projects/{projectId}/executions/{executionId}` | Full detail: redacted request snapshot, response (headers, capped body), checks, findings |

### 2.6 Contract validation — Phase 4

Findings are returned inside execution detail. Additional report endpoint:

| Method | Path | Description |
|---|---|---|
| GET | `/projects/{projectId}/runs/{runId}/validation-report` | Findings aggregated by operation, rule and severity, with counts and examples |

Finding shape:

```json
{
  "rule": "SCHEMA_TYPE_MISMATCH",
  "severity": "ERROR",
  "jsonPath": "$.items[2].price",
  "expected": "number",
  "actual": "string (\"12.50\")",
  "message": "Value at $.items[2].price is a string but the schema requires number.",
  "specPointer": "#/paths/~1orders/get/responses/200/content/application~1json/schema/properties/items/items/properties/price"
}
```

### 2.7 Test cases and generation — Phase 5

| Method | Path | Description |
|---|---|---|
| POST | `/projects/{projectId}/tests/generate` | `{operationKeys?: [], categories?: []}`. Deterministic generation against the active version; idempotent through the dedupe hash → `{created, unchanged, skippedUnsafe: [{operationKey, category, reason}]}` |
| GET | `/projects/{projectId}/tests` | `?operationKey=&origin=&reviewStatus=&lifecycleStatus=&category=` paged |
| POST | `/projects/{projectId}/tests` | Create a manual test |
| GET | `/projects/{projectId}/tests/{testId}` | Detail |
| PUT | `/projects/{projectId}/tests/{testId}` | Edit (an edited AI test returns to `PENDING_REVIEW` unless the editor approves in the same request) |
| POST | `/projects/{projectId}/tests/{testId}/review` | `{decision: "APPROVE" \| "REJECT", note?}` |
| DELETE | `/projects/{projectId}/tests/{testId}` | Archive (soft delete) |

### 2.8 Coverage and dashboard — Phase 6

| Method | Path | Description |
|---|---|---|
| GET | `/projects/{projectId}/coverage` | `?specVersionId=&environmentId=&since=`. Returns metrics (each with numerator, denominator and definition), untested operations, untested documented responses, operations missing negative tests, and per-operation breakdown |
| GET | `/dashboard` | Cross-project summary for the current user: project count, recent runs, 14-day outcome trend, lowest-coverage projects |

### 2.9 AI — Phase 7

| Method | Path | Description |
|---|---|---|
| GET | `/ai/status` | `{enabled, reachable, model, modelAvailable}` (cached for 30 s) |
| POST | `/projects/{projectId}/ai/test-suggestions` | `{operationKey, maxSuggestions ≤ 10}`. Returns suggestions stored as `PENDING_REVIEW` tests, plus rejected suggestions with reasons. With AI unavailable → `200 {source: "UNAVAILABLE", suggestions: []}` with a message (deterministic generation is unaffected) |
| POST | `/projects/{projectId}/executions/{executionId}/explanation` | Returns `{source: "AI" \| "DETERMINISTIC_FALLBACK", summary, hypotheses[], debuggingSteps[], suggestedChange, citations[]}` |
| GET | `/projects/{projectId}/executions/{executionId}/explanations` | Previous explanations for this execution |

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

// Test case
{
  "id": "…", "operationKey": "POST /pets", "name": "Missing required field 'name'",
  "category": "MISSING_FIELD", "origin": "DETERMINISTIC", "reviewStatus": "APPROVED",
  "lifecycleStatus": "ACTIVE", "riskLevel": "MUTATING",
  "request": { "pathParams": {}, "query": {}, "headers": {}, "contentType": "application/json",
               "body": { "tag": "dog" } },
  "expectations": { "expectedStatuses": ["400", "422"], "statusClassFallback": "4XX", "validateSchema": true },
  "rationale": "'name' is listed in required[] of the request schema."
}

// Coverage metric
{ "key": "operationsTested", "label": "Operations with ≥1 executed test",
  "numerator": 14, "denominator": 20, "ratio": 0.70,
  "definition": "Operations in the selected spec version that have at least one execution (any outcome except SKIPPED) in the selected window." }
```

## 4. Versioning and compatibility

- Breaking changes require `/api/v2`. Additive fields are allowed in v1.
- The frontend's TypeScript types are maintained alongside zod schemas that validate responses at runtime in development builds.
