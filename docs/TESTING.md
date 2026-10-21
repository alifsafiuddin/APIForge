# Testing Strategy

> Status: strategy for all phases; section 7 lists the tests that exist (Phases 1–2). Results are only reported in [PROJECT_STATUS.md](PROJECT_STATUS.md) after they have actually been run.

## 1. Principles

1. Every important business rule has a test that would fail if the rule were broken.
2. Security controls are tested as **negative** tests: the attack is attempted and must be blocked.
3. Tests never hit the public internet. Outbound execution tests use WireMock or the `demo-target` app via explicitly configured trusted targets.
4. Real PostgreSQL (Testcontainers) for anything involving persistence. No H2: behaviour must match production (jsonb, citext, constraints).
5. AI tests use a stub `ChatModel`. Real-Ollama tests exist, are tagged `ollama`, and are excluded from CI.
6. A test result is only reported as passed after it has actually run. Skipped and disabled tests are listed, not hidden.

## 2. Test levels

| Level | Backend | Frontend |
|---|---|---|
| Unit | JUnit 5 + AssertJ + Mockito. Pure logic: generators, outcome evaluator, address classifier, ref guard, redactor, AI output validator, variable resolver | Vitest + Testing Library: components, hooks, zod schemas, formatting |
| Slice | `@WebMvcTest` + Spring Security test (auth, CSRF, validation, problem+json); `@DataJpaTest` + Testcontainers (constraints, queries) | Page-level tests with MSW-mocked API |
| Integration | `@SpringBootTest` + Testcontainers PostgreSQL + WireMock target + MockMvc: full flows (import → generate → run → validate → coverage) | — |
| Architecture | ArchUnit (implemented in Phase 2): layering, module boundaries, "only `outbound` builds HTTP clients", "only `ai` uses Spring AI" | oxlint rules (`react/no-danger` as error, hooks rules, jsx-a11y) |
| End-to-end | — | Playwright against the Docker Compose stack (backend + Postgres + demo-target; Ollama stubbed or absent) |

## 3. Business-rule test catalogue (planned)

| Area | Rule | Test |
|---|---|---|
| Auth | Passwords stored hashed; login rotates session; lockout after 5 failures | `AuthServiceTest`, `LoginLockoutIT`, `SessionFixationIT` |
| AuthZ | Non-owner gets 404 on every project-scoped endpoint and causes no side effects | `CrossUserAccessIT` (parameterized over endpoint list) |
| CSRF | State-changing requests without a token → 403 | `CsrfIT` |
| Spec import | Valid 3.0/3.1 JSON and YAML import; invalid gives readable messages; external `$ref` rejected; size and YAML-alias limits | `RefGuardTest`, `SpecImportServiceIT`, fixtures in `src/test/resources/specs/` |
| Spec versions | Re-import creates a new immutable version; old runs still reference the old version; vanished operations mark tests `STALE` | `SpecVersioningIT` |
| SSRF | Every range in SECURITY.md §6.2 blocked, including encoded forms; mixed public/private DNS answers blocked; rebinding (different answer on second lookup) does not change the connect target; redirect to private blocked; trusted target allowed only on exact host:port; metadata never allowed | `AddressClassifierTest`, `HostParserTest`, `GuardedDnsResolverTest`, `OutboundGuardIT` |
| Limits | Timeout → `ERROR/TIMEOUT`; body over cap → truncated or `RESPONSE_TOO_LARGE`; gzip bomb capped after decompression | `OutboundLimitsIT` (WireMock delays, chunked bodies) |
| Secrets | Secret never returned by API, never in history, never in logs, never in AI context | `SecretRedactionIT`, `RedactorTest`, log-capture test |
| Generation ✅ | Same spec gives the same tests (determinism); categories produced only where applicable; every generated request is what its test claims (valid, or broken in exactly one place); unsafe or ambiguous negatives skipped with reason; regeneration is idempotent and keeps user edits | `DeterministicTestGeneratorTest` (incl. a golden scenario-key list and an invariant check over five fixture specs), `SchemaValidatorTest`, `ValueSynthesizerTest`, `TestGenerationIntegrationTest` |
| Validation | Status, content type, schema type/required/enum/format/additionalProperties findings carry the correct JSON path and spec pointer | `ContractValidatorTest` with a matrix of fixture responses |
| Outcome | Exhaustive table: any FAIL gives FAILED; any INCONCLUSIVE (no FAIL) gives INCONCLUSIVE; transport error gives ERROR; never PASSED with a skipped mandatory check | `OutcomeEvaluatorTest` (parameterized) |
| Review gate | AI-suggested test cannot run until approved; editing resets approval | `ReviewGateIT` |
| Execution policy | READ_ONLY environment refuses POST/PUT/PATCH/DELETE tests | `ExecutionPolicyIT` |
| Coverage | Metric numerators and denominators match hand-computed fixtures; INCONCLUSIVE and SKIPPED are not counted as passed | `CoverageServiceIT` |
| AI | Valid output accepted; invalid JSON, unknown operation, disallowed header, absolute URL or unknown citation rejected → fallback; Ollama down gives fallback and deterministic flows unaffected | `AiOutputValidatorTest`, `FailureAssistantIT` (stub model that throws, times out, or returns garbage) |
| Health | Ollama down does not affect readiness | `HealthIT` |

## 4. Fixtures

- `backend/src/test/resources/specs/`: `petstore3-official.yaml`, `pets-3.0.json`, `inventory-3.0.yaml`, `library-3.1.json`, `warehouse-lab-3.0.yaml` (copy of `samples/warehouse-lab-openapi.yaml`, the constraint-rich generation sample). Invalid and hostile documents are written inline in the tests.
- `demo-target/`: a small Spring Boot API with `openapi.yaml` and a few **deliberate, documented** contract deviations (wrong type in one field, undocumented 500 path, missing required field behind a query flag), so validation and the AI assistant have real failures to explain.

## 5. Coverage and quality gates (CI)

| Gate | Threshold / rule |
|---|---|
| Backend build | `./mvnw -B verify`: compile with `-Xlint:all`; unit + integration tests; ArchUnit |
| Backend line coverage (JaCoCo) | ≥ 80% overall; ≥ 90% for `outbound`, `validation`, `generation`, `secrets` packages |
| Frontend | `npm run lint`, `npm run typecheck`, `npm test -- --coverage` (≥ 70% lines on `src/features`) |
| E2E | Playwright smoke suite on the compose stack (main branch and PRs touching both apps) |
| Dependencies | `npm audit --audit-level=high`, dependency review action |
| Warnings | Warnings are fixed, not suppressed. Any `@SuppressWarnings` needs a justification comment and review |

Thresholds are enforced from the phase that introduces the relevant package. They will not be lowered to make a build pass.

## 6. Running tests

```bash
# Backend: requires Docker running (Testcontainers starts postgres:18.6-alpine)
cd backend && ./mvnw verify              # Windows: .\mvnw.cmd verify

# Frontend
cd frontend && npm ci && npm run check   # lint + typecheck + tests
npm run test:coverage

# Later phases
./mvnw test -Dgroups=ollama              # Phase 7, optional, real local Ollama required
cd e2e && npx playwright test            # Phase 8
```


## 7. Tests implemented so far (Phases 1–5)

Backend: 189 tests (JUnit 5, Testcontainers PostgreSQL 18.6, real HTTP for integration tests). Frontend: 74 tests (Vitest, Testing Library, MSW). Results of the latest run are recorded in [PROJECT_STATUS.md](PROJECT_STATUS.md).

### Backend

| Suite | Tests | What it proves |
|---|---|---|
| `AddressClassifierTest` | 54 | Every blocked range with its trust rule (loopback/private/CGNAT trustable; link-local, metadata, documentation, multicast, reserved, Teredo, discard never); IPv4 inside IPv6 (mapped, compatible, NAT64 incl. local-use, 6to4) classified as IPv4; neighbouring public addresses allowed |
| `UrlGuardTest` | 49 | Plain URLs accepted and normalized (case, trailing dot, punycode, IPv6 literal); other protocols refused (`file`, `ftp`, `gopher`, `ws`, `jar`, `dict`, `mailto`); credentials in URLs; 13 alternative IPv4 notations (`0x7f.0.0.1`, `017700000001`, `2130706433`, `127.1`, …); backslashes, spaces, NUL, percent-encoded hosts, zone ids, underscores, bad labels, Unicode dots and non-ASCII hosts, port 0 and > 65535, relative and over-long URLs |
| `DestinationPolicyTest` | 44 | One lookup per check; one blocked DNS answer blocks the name; names resolving to blocked addresses; blocked literals without DNS; local and internal names before DNS; privileged ports; DNS failures; trusted targets matched on exact host and port only; trust never unlocks link-local or metadata; trusted list ignored when disabled; **rebinding**: the client resolver returns only the validated answer without a new lookup and refuses other names; connected peer must be allowed; save-time checks without DNS; unsafe trusted-target configurations stop startup |
| `OutboundHttpClientTest` | 21 | The real client against local servers: success (status, headers, body, size, duration); GET/POST/PUT/PATCH/DELETE with bodies; 404/500 as responses; truncation at the response limit; oversized request bodies refused; read timeout; total deadline; malformed HTTP; TLS failure; DNS failure; refused connection (no address in the message); cancellation aborts the request in flight; system proxy settings ignored; untrusted local services never contacted (9 URL forms); redirects off by default, same-origin redirects keep and cross-origin redirects drop credentials; redirects to metadata, untrusted ports, localhost, `file:` and `[::1]` refused before connecting; redirect chains limited; 303 turns POST into GET; invalid Location refused |
| `SecretCipherTest` | 4 | AES-GCM round trip with a fresh IV; ciphertext bound to context, key id, key and content (tampering detected); invalid keys stop startup; the public development key is refused with the prod profile |
| `RequestFactoryTest` | 7 | Path values percent-encoded so they cannot add segments, queries or fragments; query encoding and arrays; variables in headers, query and JSON bodies; header precedence (defaults < test < auth < correlation id); unresolved variables and header injection through variables refused; bearer, basic, API-key header and query authentication with credentials redacted in snapshots (raw, URL-encoded, Base64); NONE and INVALID modes never send the real credential; snapshot body redaction and truncation |
| `RedactorAndEvaluatorTest` | 20 | Secrets removed in raw, URL-encoded and Base64 form, longest first; sensitive and configured headers masked, repeated headers kept; API-key query parameters masked (also percent-encoded names); JSON keys and values; status matching (codes, classes, default); content-type, JSON-syntax and not-evaluated schema checks; malformed JSON messages do not quote the body; execution policies; only textual bodies decoded |
| `EnvironmentIntegrationTest` | 20 | Secrets encrypted at rest (ciphertext only, key id) and never returned or audited with their value; switching a variable between secret and plain; 13 unsafe or invalid base URLs refused when saving; mutating policies need an authorization confirmation; credential and transport headers refused as defaults; variable name and value rules; optimistic locking and unique names; another user gets 404 everywhere |
| `ExecutionIntegrationTest` | 15 | Against a local authorized API: success with status, body, duration and a correlation id the target received; **secrets never in responses, history, audit or logs** (log capture) although the target echoed them; HTTP 500 as FAILED with its response; read timeout; malformed JSON; oversized and binary bodies; redirects only when enabled and never to the metadata address; destinations that became unsafe blocked at execution and audited; unresolved variables not sent; execution policy and console request rules; a run of generated tests with exactly the one documented deviation failing; read-only environments skip mutating tests; cancellation aborts the request in flight and skips the rest; per-user concurrency limit (429) and release; another user cannot execute or cancel |
| `SchemaValidatorTest` | 57 | Schema edge cases for the validator that guards generated requests: types incl. 3.0 `nullable` and 3.1 type arrays, integer vs number, enum/const as JSON values, inclusive/exclusive bounds in 3.0 and 3.1 form, exact-decimal `multipleOf`, length in code points, unanchored `pattern`, invalid pattern and catastrophic backtracking reported as unevaluated (abandoned within the read budget), array counts/uniqueness/item pointers, required vs readOnly, additionalProperties (false and schema), property counts, escaped pointers, allOf/oneOf/anyOf/not, `$ref` siblings, unsupported keywords listed, unresolvable and circular references, boolean schemas, the single-fault check |
| `ValueSynthesizerTest` | 53 | Preference order (const, examples, default, enum; invalid examples skipped); realistic samples for 10 formats; length fitting; `pattern` satisfied and deterministic, with length limits; impossible ranges, enums and patterns reported; integer/number bounds, exclusive bounds and `multipleOf` (incl. 0.01 with exclusive 0); minimal vs complete arrays, unique items, impossible uniqueness, oversized minItems; required-only vs complete objects, readOnly never sent, recursive optional (finite) and required (impossible) schemas, minProperties, schemaless required properties; allOf merge and incompatible types; oneOf with discriminator; overlapping oneOf impossible; anyOf; 3.1 type arrays, `null`, `false` |
| `DeterministicTestGeneratorTest` | 35 | Same document → identical tests in the same order; golden list of the 49 scenario keys of `POST /products`; complete metadata (rationale, location, statuses, version, unique keys); missing-field tests only for required properties; minimal request has required fields only; required header vs optional query; path parameters never omitted (reason recorded); parameter value types; risk by method; no body scenarios without a body; missing body and undocumented content type; `+json` media type with minProperties; oneOf fields skipped with reason; pattern values match; documented statuses (201/401/415/400+422), path errors 404, undocumented auth 401/403, response-schema checks, 204 without checks; public operation without auth tests; cookie/reserved headers, required non-JSON body, optional body, `*/*`, optional auth and mutual TLS, two-constraint mutations, oversized limits, unsatisfiable parameters, default-only responses, the 100-test cap, OpenAPI 3.1 bounds/const; **every generated request in five fixture specs validated against its claim** |
| `TestGenerationIntegrationTest` | 12 | Generation stores explainable tests in the GENERATED suite and audits; regenerating an unchanged spec changes no row (ids, hashes, versions, content identical); limiting to operations, unknown key → 400; no active spec → 409; generation plan stores nothing; edited, disabled and moved tests survive regeneration; a changed spec updates tests in place (same id, new source version) and archives obsolete ones; filters, search, summary, sort allow-list; bulk enable/disable with all-or-nothing ownership; review decisions; edit validation (path params, credential headers, statuses, stale version, unknown `script` field); suites (duplicate names, non-empty and generated suites cannot be deleted); another user gets 404 everywhere |
| Unit: `PointersAndMediaTypesTest` (21), `JsonValuesTest` (5), `TestCaseDefinitionTest` (6), `TestDefinitionValidatorTest` (9) | 41 | JSON pointers (escaping, copy-on-write updates, readable names); JSON media types and ranges; JSON equality across number types and member order; request/expectation storage round trip; scenario identity independent of values; refresh keeps user choices; edits mark tests and send AI suggestions back to review; edit validation rules |
| `SpecificationAnalyzerTest` | 41 | Valid JSON (pets) and YAML (inventory) documents; the official Petstore 3 sample (19 operations); responses ordered with `default` last; nested and recursive schema references kept and resolvable; arrays, enums, nullable, required and `allOf` preserved; referenced parameters, request bodies, responses and headers resolved; path-level parameters and effective security; unsupported features reported per operation; OpenAPI 3.1 type arrays and webhooks; malformed JSON and YAML with line/column; empty, binary, mis-encoded and non-object input; BOM; Swagger 2.0 and unsupported versions; missing `openapi`, `info`, `responses`, `info.title`; undeclared path parameters; external references in 7 forms (incl. the metadata IP); unresolved, malformed and non-string references; `$ref` keys inside examples ignored; duplicate YAML and JSON keys; duplicate operationIds and ambiguous paths; alias bomb, recursive anchor, deep nesting and operation-count limits; aliases expanded correctly with dates kept as strings |
| `SpecificationImportIntegrationTest` | 16 | Multipart YAML upload → active version 1 with normalized operations, schemas, servers, tags and security schemes; pasted JSON; original stored byte for byte and downloaded as an attachment; official Petstore import; missing fields → 422 with messages and nothing stored; malformed upload with line; external reference → 422 and a live local server receives 0 requests; oversized upload and pasted text → 413 (service and filter paths); dry run never persists; re-import keeps v1 with its operations and the run that references it, marks the test for a vanished operation STALE and reactivation revives it; identical content flagged; `activate=false`; auditing; another user gets 404 on every spec endpoint and cannot import |
| `RequestSizeLimitFilterTest` | 3 | Declared oversized bodies rejected before the chain; chunked bodies counted while read; small bodies untouched |
| `AuthIntegrationTest` | 14 | Registration stores only a `{bcrypt}` hash and signs in; duplicate e-mail rejected case-insensitively; field validation; weak passwords rejected by policy (nothing stored); login sets `HttpOnly`/`SameSite=Lax` session and `SameSite=Strict` CSRF cookies; unknown e-mail and wrong password give identical 401s; lockout after 5 failures even with the correct password, recovery after expiry; session id rotation (old id unusable); logout invalidates server-side (replayed cookie rejected); every protected endpoint and unknown path → 401 problem+json; public endpoints stay open; requests without CSRF token → 403 `CSRF_INVALID` with no side effect; CSRF token rotates on login and the new one works immediately |
| `RestrictedAuthConfigurationIntegrationTest` (separate context) | 2 | Registration can be disabled (403, nothing stored); per-IP sign-in limit → 429 `RATE_LIMITED` with `Retry-After` |
| `ProjectIntegrationTest` | 8 | CRUD round trip persists (trimmed names, cleared description, version increments); stale version → 409; names unique per owner among active projects (archive frees, restore conflicts, other users unaffected); per-field validation (blank, too long, control characters, unknown property, missing version); paging, sorting, literal `%`/`_` search, size cap 100, unsupported sort → 400; archived listing; 404 vs 400 for unknown vs malformed ids; delete cascades to environments and runs |
| `CrossUserAccessIntegrationTest` | 7 | A second user gets 404 on all five project-scoped read endpoints and on update/delete; foreign and missing projects are indistinguishable; owner data unchanged; listings contain only own projects; another project's run is unreachable through one's own project id; attempts are audited (random ids are not); owner sees full history |
| `SchemaIntegrationTest` | 12 | V1–V9 applied in order; generated tests need a scenario key; all tables exist; entities round-trip incl. jsonb and UUIDv7 ids; plaintext password rejected; secret variables only as ciphertext; AI test cannot be approved without review; cross-project links rejected by composite FKs; results, spec versions and audit events immutable; PASSED requires a response; deleting a test case keeps its results; deleting a user cascades owned data and keeps the audit trail |
| `ApplicationIntegrationTest` | 9 | Migrations applied; `citext`; health/readiness/liveness with DB and no details; only `health`/`info` exposed (401 anonymous, 404 signed in); system info; anonymous API → 401 problem+json with matching request id; security headers; request-id propagation |
| `ContainerErrorIntegrationTest` | 1 | Filter-level exception → problem+json without exception text |
| `ApiExceptionHandlerTest` (`@WebMvcTest` with real `SecurityConfig`) | 14 | Validation (body, params, service-level), malformed JSON, unknown properties, 405/415, `ApiException` mapping, optimistic lock → `STALE_VERSION`, integrity violation hides constraint names, access denied → 403 (not 500), anonymous → 401 via entry point, POST without CSRF → 403, 500 hides internals |
| `CorsConfigurationTest` | 4 | CORS through Spring Security: allowed origin (credentials, exposed `X-Request-Id`); other origins and unlisted headers rejected |
| `ArchitectureTest` (ArchUnit) | 10 | Controllers use neither repositories nor entities; web layer never uses repositories; repositories only used by services; entities only in `domain` packages; services independent of controllers and the servlet API; no cycles between feature modules; no Jackson 2 usage; only `outbound` uses an HTTP client or sockets; only `secrets` uses `javax.crypto` |
| Unit: `PasswordPolicyTest` (10), `UserLockoutTest` (3), `FixedWindowRateLimiterTest` (5), `AbsoluteSessionTimeoutFilterTest` (3), `PaginationTest` (3), `ProjectServiceTest` (2), `AuthDtosTest` (2), `RequestCorrelationFilterTest` (8), `ProblemErrorControllerTest` (11), `ApiForgePropertiesTest` (4) | 51 | Password rules incl. bytes vs characters and e-mail containment; lockout counter/expiry; rate-limit windows and `Retry-After`; absolute session lifetime; sort allow-list; LIKE escaping and method risk levels; password redaction in DTO `toString()`; correlation ids; container error mapping; property validation |

### Frontend

| Suite | Tests | What it proves |
|---|---|---|
| `client.test.ts` | 9 | Schema-validated success; problem+json → `ApiError`; invalid shape, network failure and bodiless 502 classified; CSRF token fetched when missing and sent only on state-changing requests; one retry with a fresh token after `CSRF_INVALID`; 204 handling; accepted 503 body parsed |
| `auth.test.tsx` | 13 | Signed-out users redirected to login with `next`; signed-in users skip the login page; session-check failure shows retry; mid-session 401 returns to login; login sends CSRF header and continues to `next`; invalid credentials message; client validation; registration success, local password checks, server field errors, `EMAIL_TAKEN` on the e-mail field; sign-out via user menu; initials |
| `redirect.test.ts` | 12 | Open-redirect protection for `next` (absolute, protocol-relative, backslash, `javascript:`, login/register loops) |
| `ProjectsPage.test.tsx` | 10 | Listing, empty state, create, duplicate-name field error, blank-name validation without a request, typed-name delete confirmation, archive/restore, pagination, search, load error with retry |
| `AppShell.test.tsx` | 4 | Active navigation, header status and account menu, mobile drawer, 404 inside the shell |
| `SystemStatusCard.test.tsx` | 3 | Live component health, DB down on 503, unreachable backend |
| `SpecificationPage.test.tsx` | 14 | Empty state → paste import → explorer; file upload sent as multipart with CSRF (asserted at the fetch boundary, see ADR-024) and findings shown; rejected import lists problems with locations; dry-run check; client-side 5 MB limit; unexpected failures shown instead of swallowed; overview (servers, schemes, download link); endpoints grouped by tag with search and method filter; endpoint detail (parameters by location, constraints, responses, headers, expanded referenced schema, security); per-endpoint warnings and request body; switching to and activating an older version; foreign project → not found; search matching |
| `EnvironmentsPage.test.tsx` | 2 | Authorization notice on the first environment; confirmation required for mutating policies (server field error shown, then saved); secret variables entered as passwords, shown as write-only, never displayed |
| `RunsPage.test.tsx` | 2 | Run dialog from the Tests workspace (policy shown, endpoint scope sent), live run page with progress and polling, result panel with redacted URL and headers, not-evaluated schema check and correlation id, cancel; console request with local JSON validation and the failed result shown |
| `TestsPage.test.tsx` | 7 | No active spec → link to Specification; generate → report with counts and skipped reasons, endpoint counts; endpoint tests with categories and statuses and the "not generated" list; single toggle sends PATCH with the version, bulk enable; test panel explains why/expected/source/auth; edit rejects invalid JSON locally and shows server field errors; suites create (duplicate name), rename, delete only when allowed; review reject/approve |
| `SchemaView.test.tsx` | 9 | Reference expansion with required, nullable, enum and constraints; collapsing deep references; recursion stops; unresolved references; 3.1 type arrays; composition; description text is not rendered as markup; type labels; JSON-pointer escapes |
