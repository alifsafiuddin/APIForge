# Testing Strategy

> Status: **Phase 0 design**. No tests exist yet. This document defines what will be tested and how. Results are only reported in [PROJECT_STATUS.md](PROJECT_STATUS.md) after they have actually been run.

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
| Architecture | ArchUnit (from Phase 2, when there are layered modules to check): layering, module boundaries, "only `outbound` builds HTTP clients", "only `ai` uses Spring AI" | oxlint rules (`react/no-danger` as error, hooks rules, jsx-a11y) |
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
| Generation | Same spec gives the same tests (determinism); categories produced only where applicable; unsafe negatives skipped with reason; regeneration is idempotent | `*GeneratorTest` per category, golden-file tests against fixture specs |
| Validation | Status, content type, schema type/required/enum/format/additionalProperties findings carry the correct JSON path and spec pointer | `ContractValidatorTest` with a matrix of fixture responses |
| Outcome | Exhaustive table: any FAIL gives FAILED; any INCONCLUSIVE (no FAIL) gives INCONCLUSIVE; transport error gives ERROR; never PASSED with a skipped mandatory check | `OutcomeEvaluatorTest` (parameterized) |
| Review gate | AI-suggested test cannot run until approved; editing resets approval | `ReviewGateIT` |
| Execution policy | READ_ONLY environment refuses POST/PUT/PATCH/DELETE tests | `ExecutionPolicyIT` |
| Coverage | Metric numerators and denominators match hand-computed fixtures; INCONCLUSIVE and SKIPPED are not counted as passed | `CoverageServiceIT` |
| AI | Valid output accepted; invalid JSON, unknown operation, disallowed header, absolute URL or unknown citation rejected → fallback; Ollama down gives fallback and deterministic flows unaffected | `AiOutputValidatorTest`, `FailureAssistantIT` (stub model that throws, times out, or returns garbage) |
| Health | Ollama down does not affect readiness | `HealthIT` |

## 4. Fixtures

- `backend/src/test/resources/specs/`: petstore-3.0.yaml, petstore-3.1.json, invalid-*.yaml, external-ref-*.yaml, yaml-bomb.yaml, large-spec generator.
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

## 7. Tests implemented so far (Phase 1)

| Suite | Tests | What it proves |
|---|---|---|
| `ApplicationIntegrationTest` (Testcontainers PostgreSQL, real HTTP on a random port) | 8 | Flyway applies V1 with no pending migrations; `citext` installed; health/readiness/liveness UP with DB component and no details; only `health` and `info` exposed; system info; 404 problem+json with `requestId` matching the header; client request id propagated |
| `ApiExceptionHandlerTest` (`@WebMvcTest`, test-only controller) | 8 | Field, parameter and service-level (`ConstraintViolationException`) validation errors list fields; malformed JSON and unknown properties give `MALFORMED_REQUEST` without parser details; 405/415 in problem format; 500 hides the exception message |
| `ContainerErrorIntegrationTest` (real server) | 1 | An exception thrown by a servlet filter (outside MVC) still yields problem+json with `INTERNAL_ERROR`, the original `instance`, a matching `requestId`, and no exception text |
| `ProblemErrorControllerTest` | 11 | Container status mapped to code; non-error or unknown statuses reported as 500; original URI and correlation header restored; exception messages never in `detail` |
| `RequestCorrelationFilterTest` | 8 | Id generated when missing; safe client ids reused; unsafe ids (CRLF injection, HTML, too short or long) replaced; MDC cleared even when the chain throws |
| `CorsConfigurationTest` | 4 | Allowed origin preflight and simple request succeed (credentials, exposed `X-Request-Id`); other origins and unlisted headers rejected |
| `ApiForgePropertiesTest` | 4 | Empty origin list allowed; wildcard or path origins fail startup |
| Frontend `client.test.ts` | 6 | Schema-validated success; problem+json mapped to `ApiError`; schema mismatch, network failure and bodiless 502 classified; accepted 503 parsed |
| Frontend `SystemStatusCard.test.tsx` | 3 | Live components and version rendered; DB DOWN on 503; unreachable backend explained |
| Frontend `AppShell.test.tsx` | 4 | Active nav item, header status, mobile drawer, 404 inside the shell |

Results of the latest run are recorded in [PROJECT_STATUS.md](PROJECT_STATUS.md).
