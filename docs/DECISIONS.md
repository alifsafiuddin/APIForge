# Architecture Decision Records

Short ADRs: context, decision, consequences. New decisions are appended. Superseded ones are marked, not deleted.

## Dependency compatibility matrix

Verified on **2026-10-20** (Phase 0) and updated with the versions actually resolved in Phase 1 (2026-10-21), against Maven Central metadata, npm, spring.io and the Spring Boot dependency-versions appendix. Exact patch versions are re-checked when each phase adds the dependency.

| Component | Version | Source / compatibility evidence |
|---|---|---|
| Java | 21 (LTS) | Installed: 21.0.8. Baseline for Boot 4 and openapi-request-validator 3.x |
| Spring Boot | **4.1.1** | Latest GA on Maven Central (4.2.0 is at M2) |
| Spring Framework / Security | 7.0.9 / 7.1.1 | Managed by Boot 4.1.1 (verified resolved versions) |
| Maven (via wrapper) | 3.10.0 | Maven Wrapper 3.3.4 from Spring Initializr; no local Maven install needed |
| Hibernate ORM | 7.4.5.Final | Managed by Boot 4.1.1 |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) | Managed by Boot 4.1.1. Boot 4 needs `spring-boot-starter-flyway` |
| PostgreSQL JDBC | 42.7.13 | Managed by Boot 4.1.1 |
| PostgreSQL server | 18.6 (`postgres:18.6-alpine`) | ✅ Verified in Phase 1: Flyway 12.4 migrates it (Testcontainers ITs and Compose) |
| Jackson 3 (`tools.jackson`) | 3.1.5 (Boot-managed) | Boot 4 default JSON stack for our own API |
| Jackson 2 (`com.fasterxml.jackson`) | 2.21.5 (Boot-managed) | Needed by swagger-parser and the validator; coexists with Jackson 3 (different packages) |
| Apache HttpClient 5 | 5.6.4 (HttpCore 5.4.3) | ✅ In use since Phase 5, only in `com.apiforge.outbound` (ArchUnit); managed by Boot 4.1.1; provides the pluggable `DnsResolver` |
| JUnit Jupiter / Mockito | 6.0.3 / 5.23.0 | Managed by Boot 4.1.1 |
| Testcontainers | 2.0.5 (Boot-managed) | ✅ Works with Docker Desktop 29.9 (Windows) in Phase 1 |
| Spring AI | **2.0.1** (BOM `spring-ai-bom`) | Latest GA; 2.0.0 release notes: "upgrade to Spring Boot 4.1.0". 2.1.0-M1 targets Boot 4.2, not used |
| swagger-parser (`io.swagger.parser.v3:swagger-parser-v3`) | **2.1.48** (swagger-core 2.2.53) | ✅ In use since Phase 3; only the OpenAPI 3 module (no Swagger 2 converter). Uses Boot-managed Jackson 2.21.5; dependency convergence passes |
| SnakeYAML | 2.6 (Boot-managed) | ✅ Phase 3: YAML loading with explicit limits (ADR-024) |
| RgxGen (`com.github.curious-odd-man:rgxgen`) | **3.1** | ✅ Phase 4: strings matching a schema `pattern` for generated test data. Apache 2.0, released 2025-08-18, no runtime dependencies; seeded `generate(RandomGenerator)` keeps output deterministic (ADR-025) |
| openapi-request-validator-core (`com.atlassian.oai`) | **3.0.0** | Successor of `swagger-request-validator-core` (relocated). Built for Java 21, Spring 7.0.7, Jackson 2.21.3, networknt json-schema-validator 2.0.1, swagger-parser 2.1.41. We override swagger-parser to 2.1.48 and verify with tests (patch-level, same API). **Not used:** ADR-027 replaced it with the project's own validator |
| ArchUnit | 1.5.1 | Test only; rules in `ArchitectureTest` |
| WireMock | — | **Not used**: outbound tests run against local JDK `HttpServer` instances (`OutboundHttpClientTest`) |
| React | 19.3.x | npm latest |
| Vite | 8.3.x | npm latest; Node ≥ 22.12 |
| TypeScript | **6.0.3** (not 7.0) | Pinned by the official `create-vite` 9.3 React template |
| Tailwind CSS (+ `@tailwindcss/vite`) | 4.3.x | Peer supports Vite 8 |
| shadcn/ui | CLI 4.22.1 via `npx` only (not a dependency) | Preset `radix-nova`. Components copied into `src/components/ui` (unified `radix-ui` package, `cn` helper from shadcn-ui/cn). Its `tailwind.css` is vendored (ADR-023) |
| React Router | 8.5.x (data mode) | Requires Node ≥ 22.22 |
| TanStack Query | 5.10x | npm latest |
| Recharts | — | **Not used**: coverage and dashboard figures are shown as numbers, tables and progress bars, which needed no chart library |
| react-hook-form / @hookform/resolvers / zod | 7.90.0 / **5.9.2** / 4.6.x | resolvers 6.0.0 has an unresolvable optional-peer conflict (`effect` 3 vs 4) under npm 10; 5.9.2 supports zod 4 |
| sonner | 2.0.x | Toasts (shadcn component, without `next-themes`) |
| Vitest / Testing Library / MSW | 5.0.x / 16.3.x / latest | Vitest peer supports Vite 8 |
| oxlint | 1.8x | Linter from the official Vite template (replaces the planned ESLint, see ADR-015) |
| Playwright | 1.64.x | E2E |
| Node.js | 24 LTS (min 22.22) | React Router 8 and Vitest engines |

---

### ADR-001 Monorepo with separate backend, frontend, demo-target
**Decision**: One repository with `backend/` (Maven), `frontend/` (npm), `demo-target/` (Maven), `e2e/`, `docs/`. There is no root Maven aggregator: the apps share no Java code.
**Consequences**: One CI workflow with separate jobs and simple per-app builds.

### ADR-002 Spring Boot 4.1.x on Java 21
**Context**: Spring AI 2.0.x is built against Boot 4.1. Spring AI 1.x targets Boot 3.5, whose OSS support ended 2026-06-30. Boot 4.1 OSS support runs until 2027-07-31 (source: endoflife.date/spring-boot, checked 2026-10-20).
**Decision**: Boot 4.1.1. Do not adopt 4.2 until GA *and* a Spring AI release built on it is GA.
**Consequences**: Boot 4 modular starters (e.g. `spring-boot-starter-flyway`) and Jackson 3 by default.

### ADR-003 Spring AI 2.0.x with Ollama
**Decision**: `spring-ai-bom` 2.0.1, `spring-ai-starter-model-ollama`. Structured output through `OllamaChatOptions.outputSchema(...)` with a schema generated from our output records (`BeanOutputConverter`). `pull-model-strategy=never`.
**Consequences**: Spring AI types are confined to the `ai` module behind an `AiGateway` interface, which keeps tests simple (stub gateway) and isolates future upgrades.

### ADR-004 Jackson 3 for the application, Jackson 2 isolated to OpenAPI libraries
**Context**: Boot 4 uses Jackson 3 (`tools.jackson`). swagger-parser and openapi-request-validator use Jackson 2 (`com.fasterxml.jackson`). The packages differ, so both can be on the classpath. Boot 4.1 still manages Jackson 2 (2.21.5).
**Decision**: Our REST layer and persistence (`jsonb` mapping) use Jackson 3. Jackson 2 `JsonNode` / `ObjectMapper` usage is confined to adapter classes in `spec/internal` and `validation/internal`, and no Jackson 2 type crosses module APIs. An ArchUnit rule enforces this.
**Consequences**: A little conversion code (string or tree) at the adapter boundary.

### ADR-005 swagger-parser for import, with local-only `$ref` resolution
**Decision**: `OpenAPIV3Parser.readContents(content, null, options)` with `resolve=true`, after our own pre-scan rejects any non-local `$ref`. Swagger 2.0 is not converted.
**Consequences**: Specs split across multiple files must be bundled before upload (documented; e.g. `redocly bundle`). This trades convenience for no network or filesystem access during import.

### ADR-006 Contract validation with openapi-request-validator-core 3.0.0 — superseded by ADR-027
**Context**: Writing response validation from scratch (status matching, content negotiation, `nullable`, `discriminator`, 3.0 vs 3.1 schema semantics) is error-prone. openapi-request-validator is mature and built on networknt json-schema-validator.
**Decision**: Use `OpenApiInteractionValidator` for response validation behind our own `ContractValidator` interface. A Phase 4 spike must confirm: (a) messages give JSON pointers we can map to `jsonPath`, `expected` and `actual`; (b) OpenAPI 3.1 behaviour; (c) a regex deadline or linear-time regex option. If (a) or (c) fails, fall back to networknt `json-schema-validator` directly, with our own status and content-type checks.
**Consequences**: The finding mapper is ours and fully unit-tested. Unsupported cases become `INCONCLUSIVE`, not `PASSED`.

### ADR-007 Apache HttpClient 5 with a guarded `DnsResolver` for outbound requests
**Context**: The JDK `HttpClient` cannot pin the resolved IP, so check-then-connect is vulnerable to DNS rebinding.
**Decision**: HttpClient 5 classic client with a custom `DnsResolver` that resolves, validates *all* addresses, and returns only validated addresses for the connect. Redirects, decompression and system proxies are disabled in the client and handled by our code. Hostname verification and SNI still use the original host.
**Consequences**: One audited code path for outbound traffic, testable with a fake resolver.

### ADR-008 Server-side sessions + CSRF instead of JWT
**Decision**: Spring Security form-style JSON login, `HttpSession` cookie (`HttpOnly`, `SameSite=Lax`), `CookieCsrfTokenRepository`. Same-origin through nginx (and the Vite proxy in dev).
**Consequences**: No tokens in `localStorage` (XSS cannot steal them) and simple logout and invalidation. In-memory sessions mean a single backend instance and logout on restart. Spring Session JDBC is the documented upgrade path, not implemented.

### ADR-009 Modular monolith, package-by-feature, ArchUnit-enforced
**Decision**: One Spring Boot app with feature modules as described in [ARCHITECTURE.md](ARCHITECTURE.md) §3. Spring Modulith was considered but rejected: ArchUnit gives enough enforcement with fewer moving parts and no extra version-compatibility surface.

### ADR-010 Immutable spec versions; tests keyed by operation key; history by snapshot
**Decision**: Every import creates a new `spec_version`. Tests reference `operation_key` (`METHOD /path`) and are marked `STALE`, not deleted, when their operation disappears. Runs record the spec version, and executions snapshot test, request and response.
**Consequences**: Re-import never corrupts history. Storage grows with versions (acceptable at ≤ 5 MB per spec).

### ADR-011 Declarative tests only
**Decision**: Tests are JSON data (request template + expectations). No scripting language, expression evaluation or template engine beyond `{{variable}}` substitution, implemented as a literal lookup.
**Consequences**: AI output can never become executable code. Less expressive than Postman scripts, by design.

### ADR-012 Application-level AES-GCM for environment secrets
**Decision**: AES-256-GCM with a key from `APIFORGE_SECRETS_KEY` and a stored `key_id` for rotation. pgcrypto was rejected because it puts the key in SQL statements and logs.

### ADR-013 Coverage computed on demand (amended by ADR-028)
**Decision**: Coverage is a query over existing tables for a spec version and window. No stored coverage tables.
**Consequences**: Always consistent with history. If it becomes slow, add a materialized snapshot later.

### ADR-014 In-process async runs, no message broker
**Decision**: Runs execute on a bounded `ThreadPoolTaskExecutor` (virtual threads for I/O inside), with persisted status and UI polling every 1–2 s. Runs left `RUNNING` at startup are marked `INTERRUPTED`.
**Consequences**: Single-instance assumption (consistent with ADR-008). No Kafka, RabbitMQ or Redis.

### ADR-015 Frontend stack; TypeScript 6.0 instead of 7.0
**Decision**: React 19 + Vite 8 + TS 6.0 + Tailwind 4 + shadcn/ui (Radix) + React Router 8 (data mode, not framework mode) + TanStack Query 5 + react-hook-form + zod + Recharts.
**Context for TS**: TypeScript 7.0 (native compiler) is `latest`, but the official `create-vite` React template pins `~6.0.3`, and the ecosystem (typescript-eslint `<6.1.0`) has not caught up.
**Linting (updated in Phase 1)**: the current Vite template ships **oxlint** instead of ESLint. We keep it, which removes the typescript-eslint version constraint. Enabled plugins: react (rules-of-hooks, exhaustive-deps, `no-danger` as error), typescript, oxc, jsx-a11y, vitest. Run with `--deny-warnings`; generated shadcn components are linted too, not excluded.
**Revisit**: TS 7 once the Vite template moves to it.

### ADR-016 No springdoc-openapi for our own API (for now)
**Context**: springdoc brings its own `swagger-core` version, which risks conflicting with the swagger-parser and validator dependency tree that the core feature relies on.
**Decision**: Document our API in [API_DESIGN.md](API_DESIGN.md) and cover it with MockMvc tests. Revisit after Phase 4 with a dependency-convergence check (`maven-enforcer` `dependencyConvergence`).

### ADR-017 Boot-native structured logging
**Decision**: `logging.structured.format.console=ecs` in prod. No logstash-logback-encoder dependency.

### ADR-018 Bundled `demo-target` as the authorized test API — implemented differently
**Decision**: A small Spring Boot app with its own OpenAPI spec and deliberate, documented contract deviations. It is the default trusted target in dev and Compose and is used by ITs and E2E tests.
**Consequences**: The demo, CI and screenshots never test third-party systems.
**Status (Phase 10)**: Implemented as `samples/warehouse-lab-server.mjs` (a dependency-free Node server for `samples/warehouse-lab-openapi.yaml`, with documented deliberate deviations) and, in backend tests, the in-process `TargetApi`. No `demo-target/` module exists.

### ADR-019 Default model `llama3.2:3b`, configurable — superseded by ADR-029
**Context**: The model must run on a typical laptop CPU and support Ollama structured outputs. Thinking-style models emit reasoning that complicates strict JSON output.
**Decision**: Default `llama3.2:3b`, overridable through `APIFORGE_AI_MODEL`. Phase 7 includes a short evaluation of 2–3 small models against fixed fixtures (valid-output rate, latency), with the results recorded here.

### ADR-020 AI is optional at runtime (amended by ADR-029)
**Decision**: `APIFORGE_AI_ENABLED` toggles the feature. The Ollama health indicator is excluded from readiness, and every AI endpoint has a defined fallback response.

### ADR-021 OpenAPI version support
**Decision**: Import accepts OpenAPI 3.0.x and 3.1.x. Contract validation is fully supported for 3.0.x. For 3.1.x, support level is decided by the Phase 4 spike: any keyword the validator cannot evaluate produces a `VALIDATION_UNSUPPORTED` finding and an `INCONCLUSIVE` check. Swagger 2.0 is rejected with a message suggesting conversion.

### ADR-022 Phase 1 foundation choices
- **Errors**: `type: about:blank` plus a stable `code` enum, rather than inventing `type` URIs on a domain we do not own. A custom `ProblemErrorController` replaces Boot's `/error` JSON, so errors raised outside MVC share the format.
- **Correlation**: a servlet filter (highest precedence) owns `X-Request-Id`, the `requestId` MDC key, and a one-line access log (method, path, status, duration; never query strings, headers or bodies, which may hold credentials).
- **No build timestamp**: Boot's reproducible-build output timestamp makes build-info time a fixed 1980 date, so build time is disabled (`<time>off</time>`) rather than displayed falsely.
- **Health semantics**: readiness includes the database; liveness does not (a DB outage must not cause restarts). Component statuses are public, details are never shown.
- **Compiler strictness**: `-Xlint:all,-processing` with `failOnWarning`. `processing` is excluded because it only reports that the configuration-metadata processor did not claim unrelated annotations.
- **Mockito agent**: loaded explicitly through surefire `argLine` (future JDKs forbid self-attach).
- **Frontend chunks**: vendor libraries split into `react`, `router`, `ui` and `vendor` chunks (Rolldown `codeSplitting`) instead of raising the 500 kB warning limit.
- **Auth moved to Phase 2**: the Phase 1 brief covers infrastructure only. Spring Security is added together with authentication so that no permissive placeholder security config ever exists.

### ADR-023 Phase 2: schema, authentication and authorization choices
- **Table names follow the Phase 2 brief** (`users`, `api_projects`, `specification_versions`, `environments`, `test_suites`, `test_cases`, `test_runs`, `test_results`, `audit_events`). `test_suites` is new compared with the Phase 0 design: test cases belong to a suite (generated, AI suggestions, custom). `test_results` is the Phase 0 `test_execution`.
- **No coverage snapshot table** (optional in the brief): coverage is derived from persisted tests and results (ADR-013).
- **Integrity in the database**: composite FKs keep children inside one project; check constraints prevent plaintext passwords, plaintext secrets, unreviewed AI approvals and "PASSED without a response"; triggers make results, specification versions and audit events append-only. Enforcement cannot be skipped by a code path that forgets a rule.
- **Ids by reference, not JPA associations**: entities hold foreign-key ids; authorization-relevant lookups are explicit, owner- or parent-scoped repository queries.
- **UUIDv7** ids from Hibernate's `@UuidGenerator(style = VERSION_7)`.
- **JSON login endpoint + explicit session strategies** instead of Spring form login: the SPA needs JSON responses, so session-id rotation and CSRF token rotation are applied explicitly in `SessionAuthenticator` (and tested).
- **Lock enforced after password verification** and one message for every sign-in failure, so neither the response nor its timing reveals which accounts exist or are locked. Simple fixed 15-minute lockout after 5 failures (the Phase 0 "exponential up to 1 h" was dropped as unnecessary complexity alongside per-IP rate limiting).
- **Audit events join the caller's transaction by default**. An earlier draft wrote every event in a new transaction; for registration that would self-deadlock (the new transaction's FK check waits on the uncommitted user row held by the caller). Only events that must survive a rejected request (`CROSS_USER_ACCESS_DENIED`, failed sign-ins) use their own transaction, and they reference committed rows only.
- **CORS moved from Spring MVC into the security filter chain** so preflight requests are answered before authentication.
- **Logout auditing lives in the `auth` module** (listener on Spring Security's `LogoutSuccessEvent`), which keeps `audit` free of a dependency on `auth` (ArchUnit cycle rule).
- **Frontend**: the shadcn CLI is not a project dependency. Its dependency tree carries an unpatched advisory (`braces` ≤ 3.0.3 via `fast-glob`), and the only runtime need was one CSS file, now vendored at `src/styles/shadcn-tailwind.css` (MIT, attribution in the file). `npm audit` reports 0 vulnerabilities. Components are added with `npx shadcn@4.22.1 add …`.
- **No suppressions**: lint findings were fixed (semantic elements instead of ARIA roles, helpers moved out of component files, `autoFocus` removed) rather than disabled.

### ADR-024 Phase 3: specification import pipeline
- **Order of defences**: size check → strict UTF-8 → our own parse with limits → OpenAPI version check → `ReferenceGuard` (every `$ref` must be a resolvable local pointer) → swagger-parser on a JSON string → semantic checks. swagger-parser never sees raw user YAML and never has anything external to resolve, which is what makes "no outbound requests during import" a structural guarantee rather than a configuration detail.
- **YAML via SnakeYAML, not Jackson's YAML parser**. The analyzer test for the alias bomb exposed that Jackson's YAML parser does not expand aliases at all: it returns `*name` as a plain string, so a spec that uses anchors would import silently corrupted and no alias limit ever applied. SnakeYAML expands aliases under `maxAliasesForCollections`, nesting and code-point limits, with duplicate and recursive keys disallowed and recursive structures rejected. Its resolver is restricted to JSON-compatible scalars: timestamps stay strings and only `true/false` are booleans (YAML 1.1 `yes/no/on/off` are not).
- **Messages, not exceptions**: every finding is an `ImportMessage {severity, code, message, location}`; any ERROR rejects the import with 422 and the full list, while warnings and notes are stored with the version and shown in the explorer. swagger-parser's free-text messages are mapped to codes (missing/invalid/unexpected field); unknown ones become `PARSER_NOTICE` warnings.
- **Checks swagger-parser does not do**: undeclared path template parameters (it accepts `/pets/{id}` without a parameter), ambiguous paths that differ only by parameter names, duplicate `operationId`s, operation and path-length limits, and "documented but not testable" features (callbacks, webhooks, links, cookie parameters, non-JSON-only request bodies, OAuth2/OpenID Connect/mutual TLS schemes, OpenAPI 3.1 validation scope).
- **Normalization**: per operation, effective parameters (path + operation level) and resolved parameter/request-body/response/header references; schema `$ref`s are kept and resolved by clients against stored `component_schemas`, which keeps recursive schemas finite and storage proportional to the document. Serialization uses swagger-core's `Json` / `Json31` mappers so 3.1 `type` arrays survive.
- **Versions are immutable and numbered per project** under a row lock on the project (concurrent imports get consecutive numbers). Re-importing identical content is allowed and flagged (`IDENTICAL_CONTENT`). Activation marks tests whose operation key disappeared as `STALE` and revives returning ones; old versions, their operations and the runs that reference them are never modified.
- **Operation index is not paged**: it is bounded by the 2,000-operation import limit and the explorer searches it client-side; all other collections remain paged.
- **Request size cap for all bodies** (`RequestSizeLimitFilter`, in `config` to keep `common` free of a dependency on `config`; the ArchUnit cycle rule caught the first placement).
- **Frontend test limitation**: Vitest 5's jsdom bridge cannot stream a jsdom `FormData` through Node's fetch (content-length mismatch), so the component test for file upload asserts the multipart request at the `fetch` boundary; the real multipart upload is verified end to end in a browser against the backend (Phase 3 completion check) and by backend integration tests.

### ADR-025 Phase 4: deterministic test generation
- **The generator works on stored, normalized data only** (`generation.engine`): the operation rows and component schemas saved at import. It needs no OpenAPI library, no network and no database, so it is a pure function. Unit tests run it on the real import analyzer's output, round-tripped through JSON exactly as jsonb would return it (`GenerationFixtures`).
- **Every generated value is validated.** A small request-oriented JSON Schema validator (`SchemaValidator`) checks valid requests before they are used and each negative request after mutation. It covers types (including 3.0 `nullable` and 3.1 type arrays), enum/const, inclusive and exclusive bounds in both 3.0 and 3.1 form, `multipleOf` in exact decimals, length in code points, `pattern`, items/uniqueness, properties/required/additionalProperties/readOnly, and allOf/oneOf/anyOf/not. Keywords it does not evaluate (`if/then/else`, `patternProperties`, `prefixItems`, …) are reported as "not verified" in the test's rationale rather than ignored silently.
- **Single-fault rule.** A negative scenario is kept only if validation shows that the changed value breaks exactly the targeted constraint, at exactly that location. Otherwise it is recorded as skipped together with the conflicting constraint (for example, "shorter than minLength would also break the pattern"). It is never stored as a misleading test.
- **What is generated:**
  - A minimal valid request (required parameters and properties only), plus a complete one (all documented fields) when the two differ.
  - Authentication without credentials and with invalid credentials, when the operation requires authentication.
  - Missing required query and header parameters.
  - For parameters and body fields: wrong types; invalid enum values; values below, above and at numeric bounds; too short, too long and at string-length bounds; too few, too many and at array-length bounds. Body fields are explored through nested objects and the first array item, three levels deep.
  - A missing request body when the body is required, and an undocumented content type.
- **What is reported as skipped, with the reason:** missing path parameters, cookie parameters, fields inside oneOf/anyOf, non-JSON bodies, limits above 10,000 characters or 100 items, unsatisfiable schemas, and scenarios beyond 100 per operation. String parameters never get "wrong type" tests, because everything in a URL is text.
- **Explicit expectations from the document:**
  - Success: the documented 2XX statuses.
  - Invalid input: whichever of 400/422 are documented (path parameters 400/404/422, content type 415, missing body 400/415/422); if none are, the 4XX class, and the summary says so.
  - Authentication: the documented 401/403, or both with the HTTP rationale.
  - Each expectation lists the documented response bodies (status, media type, schema) the response will be validated against.
- **Values, in order of preference:** const, documented examples, default, enum, oneOf/anyOf alternatives (setting the discriminator), then values synthesized from type and constraints (realistic format samples, `pattern` via RgxGen seeded by the value's position, bounds and `multipleOf` in exact decimals). readOnly properties are never sent. A `pattern` that cannot be evaluated makes a value impossible rather than unverified: Java-incompatible syntax, or more than 200,000 character reads (which stops catastrophic backtracking). The same applies to recursive required properties deeper than 8 levels.
- **Stable identity and idempotent regeneration:**
  - Each scenario has a readable key (`body.missing:/name`, `query.above-maximum:limit`, `auth.none`), and `dedupe_hash = SHA-256(operation key, scenario key)`. The hash does not depend on generated values, so regenerating after a document change updates the same row (same id, suite, enabled flag and history).
  - Unchanged scenarios are not rewritten, and tests the user edited (`user_modified`) are never overwritten.
  - Generated tests whose scenario no longer applies are archived, unless the user edited them.
  - Generation locks the project row, so concurrent runs serialize.
- **Curation:**
  - Enable or disable tests.
  - Move them between suites: one GENERATED suite per project plus custom suites, and only empty custom suites can be deleted.
  - Edit the request, expectations and rationale. Edits are validated: path parameters must match the path, credential and transport headers are refused, and the body is bounded.
  - Review (approve or reject). Archived tests are history, not workspace items.
- **Left out of scope on purpose:** pattern-violation tests (a "non-matching" string cannot be reliably told apart from a type or length fault), creating tests from scratch, and AI suggestions (the `origin`/review model already supports them).
- **Engineering notes:** RgxGen was chosen over hand-written regex synthesis after checking its release date, license and dependency tree. List rows include the optimistic-lock `version`, so toggling a single test needs no extra fetch. JDBC batching (`hibernate.jdbc.batch_size: 50`) is on because one generation can write hundreds of rows.

### ADR-026 Phase 5: secure execution engine
- **One outbound path.** Only `com.apiforge.outbound` may use an HTTP client or sockets (ArchUnit forbids `org.apache.hc`, `java.net.http`, `HttpURLConnection` and `Socket` elsewhere). `OutboundHttpClient` is Apache HttpClient 5 with a pooling connection manager (64 connections, 8 per route, 60 s TTL, header count and line limits), no proxy (system properties are not read), and no automatic redirects, retries, cookies, auth caching or decompression.
- **Validate, then connect only there.** `UrlGuard` parses strictly: http/https only, no user info, the authority must be exactly host[:port], LDH host names, punycode instead of Unicode, IPv6 without zone ids, and IPv4 only as canonical dotted quads (`0x7f.1`, `2130706433`, `127.1` and similar are refused). `DestinationPolicy` refuses local and internal names before DNS, resolves once, and blocks the request if any answer is in a blocked range (`AddressClassifier`, with IPv4 extracted from mapped, compatible, NAT64 and 6to4 IPv6). `GuardedDnsResolver` hands the client exactly those addresses, so a changed DNS answer (rebinding) is never used. After connecting, the peer address is classified again.
- **Trusted targets** (development only) match exact host and port and may reach loopback, private and CGNAT addresses, never link-local or metadata addresses. They are off by default, refused with the `prod` profile without an explicit acknowledgement, and a trusted literal in a never-trustable range stops startup. The dev profile trusts only the local sample API on port 8090.
- **Redirects** are followed only when the environment enables it. Each hop is parsed and checked again, credentials are dropped when the origin changes, 303 (and 301/302 for non-GET) become GET without a body, and chains stop after 5 hops. A refused hop is `REDIRECT_BLOCKED`.
- **Limits:** a configurable connect timeout (default 5 s, at most 30 s), a per-environment read timeout (at most 60 s), and a total deadline per request including redirects (30 s) that aborts the request. Bodies: request ≤ 1 MB; the response is read up to 2 MB, then the connection is aborted and the body marked truncated; at most 256 KB of text is stored, and binary bodies are not stored.
- **Per-user limits:** 2 concurrent executions (runs or console requests), 300 requests per minute (runs wait for budget, console requests get 429), 500 tests per run; the global pool runs 8 runs at once and queues the rest. On startup, runs left unfinished by a previous process become `INTERRUPTED`.
- **Secrets** are AES-256-GCM encrypted with a random IV and bound to environment and variable name as associated data (`SecretCipher`, the only user of `javax.crypto`). They are write-only in the API and decrypted only into the in-memory plan of a run. The `Redactor` removes secret values (raw, URL-encoded, Base64, and the Basic token), sensitive headers and API-key query parameters from request and response snapshots and from error messages before anything is stored. Credentials are redacted even when kept in a plain variable.
- **Requests are built, never concatenated from user strings:** path values are percent-encoded (a value cannot add segments, a query or a fragment), query names and values are encoded, `{{VAR}}` references are resolved with a closed pattern, and header values with line breaks are refused even when they come from variables.
- **Correlation id:** the header is `X-APIForge-Request-Id`, not `X-Request-Id`, because the Warehouse sample (and many real APIs) document `X-Request-Id` themselves. Overriding it would have made the generated "missing required header" test unable to omit the header. Found while preparing the completion check.
- **Outcomes stay honest:** only status, content type and JSON syntax are checked in this phase. Schema validation is listed as `NOT_EVALUATED` with the reason; it is not treated as a pass.
- **Sample target:** `samples/warehouse-lab-server.mjs` is a dependency-free Node server for the Warehouse Lab contract, with one documented deviation (quantities above 999 accepted) and diagnostic endpoints for timeouts, malformed responses, redirects and large bodies. The integration tests use a JDK `HttpServer` (`TargetApi`) on the trusted test port 18090.

### ADR-027 Phase 6: response contract validation with our own validator
**Context**: ADR-006 planned to use openapi-request-validator for responses, subject to a spike on JSON paths, OpenAPI 3.1 behaviour and regex safety. By Phase 6 the project already had a schema validator (built for test generation, ADR-025) that reports JSON pointers, handles 3.0 `nullable`/boolean exclusive bounds and 3.1 type arrays, evaluates `pattern` under a backtracking budget, and lists keywords it cannot evaluate instead of ignoring them.
**Decision**: Move that validator to a shared `com.apiforge.schema` module and use it for responses; no new dependency. It gains a direction: in responses `writeOnly` properties must not appear and are not required, `readOnly` ones are required like any other, and the common formats (`date`, `date-time`, `time`, `uuid`, `email`, `ipv4`, `ipv6`, `hostname`, `uri`, `uri-reference`, `byte`, `int32`, `int64`) are asserted; in requests formats stay annotations. Every violation carries `expected` and `actual` text. `com.apiforge.validation.ContractValidator` adds the HTTP-level checks:
- `STATUS_DOCUMENTED`: the status must match a documented response — exact code, then class (`4XX`), then `default`. An undocumented status is a `FAIL` and the body is reported `SKIPPED` (there is no schema to use).
- `CONTENT_TYPE`: a body must come with a documented media type (exact match before ranges such as `application/*`); a missing or undocumented type is a `FAIL`.
- `JSON_SYNTAX`: any body that declares JSON must parse (numbers kept exact as `BigDecimal`/`BigInteger`, so `multipleOf: 0.01` works).
- `BODY_SCHEMA`: one `FAIL` assertion per violation with a JSON path (`$.items[0].price`), keyword, expected and actual, capped at 50 plus a count of the rest.
The test's own expectation stays `STATUS`. A request that matches no operation of the active specification gets `STATUS_DOCUMENTED` `SKIPPED`.
**Honest outcomes**: `SKIPPED` is used whenever a check could not run — response documented without a body or schema, undocumented status, content-type mismatch, invalid JSON, schema validation turned off for the test — always with the reason. `INCONCLUSIVE` is used when it ran but cannot decide: truncated body, non-JSON body with a schema, keywords the validator does not support (`if`/`then`, `prefixItems`, unresolvable references, invalid patterns). The outcome is `FAILED` if any assertion failed, otherwise `INCONCLUSIVE` if any was inconclusive, otherwise `PASSED`; skipped assertions are never counted as passes, the result list shows how many checks were skipped, and the result panel says so next to a pass.
**Generated tests validate whatever comes back.** Phase 4 derived `validateResponseSchema` from whether the *expected* statuses documented a body, which silenced the body of an unexpected success (the sample's quantity deviation returns 201 with a body that breaks `maximum: 999`). Generated tests now always validate the returned status's schema (generator version 2); users can still turn it off per test.
**Reporting**: results can be filtered by outcome; a run can be compared with the previous run of the same kind (or any run as baseline), matching tests by test-case id, otherwise operation and name, and classifying each as `FIXED`, `REGRESSED`, `CHANGED`, `UNCHANGED`, `NEW` or `REMOVED`. The time until the response headers arrived is recorded next to the total duration.
**Consequences**: One validator for requests and responses, with one set of edge-case tests. Formats beyond the listed ones, and 3.1 keywords not listed in ADR-025, are reported rather than guessed. The aggregated per-rule validation report planned in API_DESIGN §2.6 was not built; findings are per result.

### ADR-028 Phase 7: specification coverage and missing-scenario analysis
**Context**: The brief asks for coverage and quality metrics, each clearly defined, with endpoint coverage separate from the pass rate, without claiming code coverage, without double-counting duplicated tests, and with changes tracked across specification versions.
**Decision**: Coverage is specification coverage of the **active** version, computed on demand (ADR-013) by a pure `CoverageCalculator`, so every number can be checked by hand:
- **Enabled test** = enabled, approved and active; nothing else covers anything. **Duplicates** = enabled tests of one operation sending the same request (path values, query, headers, body, auth mode, compared as canonical JSON) with the same expected statuses; they count once. All test counts are distinct tests.
- **Test design** metrics (what the tests that exist cover): operations with and without tests; documented responses *targeted* (an expected status that would match the response: exact code, else class, else `default`; a class expectation targets the documented class and its codes); **request constraints** — the single-fault and boundary scenarios the deterministic generator derives from the contract (required inputs, types, bounds, lengths, item counts, enums, content type, accepted boundaries), covered by an enabled test of that scenario key (scenarios the generator cannot build are listed with the reason, not counted); **schema branches** — each `oneOf`/`anyOf` alternative in a request body, exercised when an enabled test's body validates against it at that location (recursive schemas are listed where they first appear); **negative scenario kinds** — per operation, which of credentials, missing required input, wrong type, out of range, outside an enum and unsupported content type apply (the generator plans one) and have a test.
- **Execution** metrics (what runs showed), from test runs on this version only (console requests excluded), optionally of one environment: a distinct test's outcome is its latest *attempted* execution (an attempt beats a newer skip; a skip-only test is skipped; none is not run); *executed* excludes skipped; the pass rate is passed/executed and is labelled as an execution result, not coverage; documented responses *exercised* are those actually received, whatever the test expected (results of since-deleted or disabled tests still count, because they are facts about the API), and undocumented statuses received are listed.
- **Gaps**: endpoints without tests, documented responses not exercised (marked whether a test expects them), missing negative kinds by operation; a per-operation drill-down lists responses, negative kinds, every planned scenario with its covered state, skipped scenarios with reasons, branches and the enabled tests with duplicates and latest outcomes.
- **Version changes**: operations added, removed (their tests are kept as STALE) and changed (parameters, request body, responses, security, or a transitively referenced component schema), with enabled tests still generated from an earlier version. To compare numbers across versions, summary numbers are stored in `coverage_snapshots` (V11) after test generation, after each finished test run and on activation — via `TestsGenerated`, `TestRunFinished` and `SpecificationActivated` events handled after commit; an unchanged summary is not stored again (except on activation), and a failure to record never fails the action.
**Consequences**: "Known constraints" are what APIForge's generator recognizes; constraints it cannot test one at a time (patterns, formats, minProperties/additionalProperties, oneOf contents) are not in the denominator, and the UI says so. A test written by hand counts for operation, response and duplicate metrics but has no scenario key, so it does not cover a constraint or a negative kind. The page always states that this is not code coverage. Generation plans are cached per (immutable) version.

### ADR-029 Phase 8: local AI through Ollama and Spring AI
**Context**: AI must help without becoming a dependency: deterministic generation, execution, contract validation and coverage have to work without it, model output must never execute or change anything on its own, and secrets must stay out of prompts.
**Decision**:
- **Spring AI 2.0.1** (latest stable; 2.1 is a milestone), only its `spring-ai-ollama` module, used through `OllamaApi` in one gateway (`ai.client.OllamaGateway`; ArchUnit confines `org.springframework.ai` to that package). Not the starter: the client is built explicitly with connect/read timeouts, no proxy, no automatic retries and our own error handler. It needs `antlr4-runtime` aligned to 4.13.2 for dependency convergence (patch level). Configuration by environment: `APIFORGE_AI_ENABLED`, `OLLAMA_BASE_URL` (validated: http(s), host, no credentials/query), `APIFORGE_AI_MODEL`.
- **Structured output**: each task has one strict JSON schema, sent as Ollama `format` and used again to validate the answer with the shared schema validator, then bound to records with unknown properties rejected. A malformed or non-conforming answer is `INVALID_OUTPUT`.
- **Reliability**: availability check by listing models (cached 10 s, refresh on demand); failures classified as disabled, unavailable, model not found, timeout, overloaded, busy, invalid output, too large or model error, each with its own HTTP error (503/504/502/413). At most 2 attempts, and only overload and invalid output are retried (timeouts and unavailability never); 1 concurrent call by default with a 2 s queue wait; 10 requests per user per minute; prompts above 24 000 characters are refused before calling. No database transaction is open during a model call.
- **Privacy and audit**: prompts carry only sanitized data (stored results are already redacted; a second pass removes sensitive keys, tokens, JWTs, opaque strings and hosts, and bounds sizes). Prompts are never stored or logged; each request is audited (`AI_SUGGESTIONS_REQUESTED`, `AI_EXPLANATION_REQUESTED`) with model, outcome, attempts, latency, sizes and the prompt's SHA-256.
- **Suggestions** are request data validated like a manual edit, compared with the existing tests (identical request and statuses discarded unless the model gives a reason; one differing value flagged as a possible duplicate), and stored as `AI_SUGGESTED` tests `PENDING_REVIEW` in an "AI suggestions" suite (V12 adds `ai_model`). They cannot run until approved; accept/edit/reject reuse the test review and edit endpoints.
- **Explanations** separate observed facts from hypotheses. Facts citing concrete values are checked against the data the model was given (VERIFIED / UNVERIFIED / NOT_CHECKABLE), claims of having read source code are flagged, the input list and `sourceCodeProvided: false` are part of every answer, and suggested changes are proposals only. Explanations are stored (V12 `ai_explanations`).
- **No deterministic "fallback explanation"** (planned in ADR-020 and API_DESIGN): when AI is unavailable the API says so plainly (503) and the UI disables the AI actions with the reason, while the result view keeps showing the deterministic assertions with expected and actual values. A non-AI text dressed as an explanation would blur what the model produced.
- **Default model `qwen2.5:7b`** (replaces ADR-019's `llama3.2:3b`), chosen by the evaluation in docs/AI_EVALUATION.md: both models produced valid structured output for every request and equally relevant explanations, but `qwen2.5:7b` made clearly better suggestions; `llama3.2:3b` is the documented choice for small machines.
**Consequences**: AI quality depends on the local model and hardware and is measured, not assumed (docs/AI_EVALUATION.md, `evaluation/ai/`). The evaluation found and fixed a crash of the shared schema validator on `null` values.


### ADR-030 Phase 11: containers, one origin, Compose profiles and proxy trust
**Context**: The app must run reproducibly from a clean machine and deploy cheaply, without secrets in images, without a public database or model server, and without weakening the cookie-based session and CSRF design (ADR-008).
**Decision**:
- **Two images**: the backend (Temurin 21 JRE, layered Spring Boot jar, non-root, `prod` profile, readiness health check) and the frontend (the built SPA in `nginx-unprivileged`). nginx also forwards `/api/` and only the aggregated `/actuator/health` to the backend, so the browser always talks to **one origin** and the session and CSRF cookies stay first-party. Static hosting of the SPA on another site is not supported.
- **One Compose file** with profiles: the base stack (PostgreSQL with a named volume and loopback-only port, backend not published, frontend on loopback), `ai` (Ollama with a model volume, never published), `tls` (Caddy with automatic certificates), `demo` (the sample API as the only trusted target, local evaluation and CI only). Startup is ordered by health checks; containers are read-only where they can be; logs rotate. Configuration and secrets come from `.env`/the environment at runtime.
- **Client address**: the backend honours forwarding headers only from private-network proxies (Tomcat `native` strategy), and nginx replaces client-supplied forwarding headers unless `APIFORGE_PROXY_TRUSTED=true` (only behind Caddy or a load balancer). Docker's port publishing makes every client appear to come from a private gateway address, so without this a client could choose the address used for rate limits and the audit trail (found by the Phase 11 stack check).
- **CI**: the images are built and the complete stack is started from a clean environment in every pull request, and the end-to-end suite runs against it; OSV-Scanner runs on the backend SBOM and the lockfiles; releases publish images to GHCR with `GITHUB_TOKEN` only.
- **Hosting**: recommended is one small VM with the Compose stack and the tls profile; a container platform with managed PostgreSQL over private networking is the alternative. Free tiers are documented with their limits rather than assumed (DEPLOYMENT.md §11).
**Consequences**: A deployment is `docker compose --profile tls up -d` plus a `.env` and backups; the database and the model server are never internet-facing; single backend instance remains a constraint (ADR-008). Ollama in a container needs several GB of disk and memory, so the native installation reached through `host.docker.internal` is documented as an equal option.
