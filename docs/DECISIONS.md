# Architecture Decision Records

Short ADRs: context, decision, consequences. New decisions are appended. Superseded ones are marked, not deleted.

## Dependency compatibility matrix

Verified on **2026-10-20** (Phase 0) and updated with the versions actually resolved in Phase 1 (2026-10-21), against Maven Central metadata, npm, spring.io and the Spring Boot dependency-versions appendix. Exact patch versions are re-checked when each phase adds the dependency.

| Component | Version | Source / compatibility evidence |
|---|---|---|
| Java | 21 (LTS) | Installed: 21.0.8. Baseline for Boot 4 and openapi-request-validator 3.x |
| Spring Boot | **4.1.1** | Latest GA on Maven Central (4.2.0 is at M2) |
| Spring Framework / Security | 7.0.9 / 7.0.x | Managed by Boot 4.1 (Framework version resolved in Phase 1; Security added in Phase 2) |
| Maven (via wrapper) | 3.10.0 | Maven Wrapper 3.3.4 from Spring Initializr; no local Maven install needed |
| Hibernate ORM | 7.4.5.Final | Managed by Boot 4.1.1 |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) | Managed by Boot 4.1.1. Boot 4 needs `spring-boot-starter-flyway` |
| PostgreSQL JDBC | 42.7.13 | Managed by Boot 4.1.1 |
| PostgreSQL server | 18.6 (`postgres:18.6-alpine`) | ✅ Verified in Phase 1: Flyway 12.4 migrates it (Testcontainers ITs and Compose) |
| Jackson 3 (`tools.jackson`) | 3.1.5 (Boot-managed) | Boot 4 default JSON stack for our own API |
| Jackson 2 (`com.fasterxml.jackson`) | 2.21.5 (Boot-managed) | Needed by swagger-parser and the validator; coexists with Jackson 3 (different packages) |
| Apache HttpClient 5 | 5.6.4 | Managed by Boot 4.1.1; provides the pluggable `DnsResolver` |
| JUnit Jupiter / Mockito | 6.0.3 / 5.23.0 | Managed by Boot 4.1.1 |
| Testcontainers | 2.0.5 (Boot-managed) | ✅ Works with Docker Desktop 29.9 (Windows) in Phase 1 |
| Spring AI | **2.0.1** (BOM `spring-ai-bom`) | Latest GA; 2.0.0 release notes: "upgrade to Spring Boot 4.1.0". 2.1.0-M1 targets Boot 4.2, not used |
| swagger-parser (`io.swagger.parser.v3:swagger-parser`) | **2.1.48** | Latest on Maven Central; Jackson 2 |
| openapi-request-validator-core (`com.atlassian.oai`) | **3.0.0** | Successor of `swagger-request-validator-core` (relocated). Built for Java 21, Spring 7.0.7, Jackson 2.21.3, networknt json-schema-validator 2.0.1, swagger-parser 2.1.41. We override swagger-parser to 2.1.48 and verify with tests (patch-level, same API) |
| ArchUnit | latest 1.x | Test only |
| WireMock | Boot-compatible 3.x | Test only |
| React | 19.3.x | npm latest |
| Vite | 8.3.x | npm latest; Node ≥ 22.12 |
| TypeScript | **6.0.3** (not 7.0) | Pinned by the official `create-vite` 9.3 React template |
| Tailwind CSS (+ `@tailwindcss/vite`) | 4.3.x | Peer supports Vite 8 |
| shadcn/ui CLI | 4.22.1 (dev dependency) | Preset `radix-nova`. Components copied into `src/components/ui` (unified `radix-ui` package, `cn` helper from shadcn-ui/cn) |
| React Router | 8.5.x (data mode) | Requires Node ≥ 22.22 |
| TanStack Query | 5.10x | npm latest |
| Recharts | 3.10.x | npm latest |
| react-hook-form / zod | 7.90.x / 4.6.x | npm latest |
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

### ADR-006 Contract validation with openapi-request-validator-core 3.0.0
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

### ADR-013 Coverage computed on demand
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

### ADR-018 Bundled `demo-target` as the authorized test API
**Decision**: A small Spring Boot app with its own OpenAPI spec and deliberate, documented contract deviations. It is the default trusted target in dev and Compose and is used by ITs and E2E tests.
**Consequences**: The demo, CI and screenshots never test third-party systems.

### ADR-019 Default model `llama3.2:3b`, configurable
**Context**: The model must run on a typical laptop CPU and support Ollama structured outputs. Thinking-style models emit reasoning that complicates strict JSON output.
**Decision**: Default `llama3.2:3b`, overridable through `APIFORGE_AI_MODEL`. Phase 7 includes a short evaluation of 2–3 small models against fixed fixtures (valid-output rate, latency), with the results recorded here.

### ADR-020 AI is optional at runtime
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
