# CLAUDE.md

Rules for AI coding agents (Claude Code and similar) working in this repository. They describe how the project
is actually built and checked; when this file and the code disagree, the code wins and this file gets fixed.

## Project

APIForge AI imports OpenAPI 3.0/3.1 specifications, generates deterministic tests, executes them against APIs
the user is authorized to test, validates responses against the contract and reports coverage. A local model
(Ollama through Spring AI) only suggests tests and explains failures.

| Part | Stack | Where |
|---|---|---|
| Backend | Java 21, Spring Boot 4.1, Spring Security (sessions + CSRF), JPA/Hibernate, Flyway, Spring AI 2.0 | `backend/` |
| Frontend | React 19, TypeScript 6 (strict), Vite 8, Tailwind 4, TanStack Query, zod | `frontend/` |
| Database | PostgreSQL 18, migrations in `backend/src/main/resources/db/migration` | |
| End-to-end | Playwright against the running stack | `e2e/` |
| Delivery | Docker Compose (profiles `ai`, `tls`, `demo`), GitHub Actions | `docker-compose.yml`, `.github/workflows/` |

Design and decisions: `docs/ARCHITECTURE.md`, `docs/DECISIONS.md`, `docs/SECURITY.md`. Current state and verified
results: `docs/PROJECT_STATUS.md`.

## Commands

```bash
docker compose up -d --wait postgres          # development database (needs .env from .env.example)
cd backend && ./mvnw spring-boot:run          # dev profile, http://localhost:8081
cd backend && ./mvnw -B verify                # compile (warnings are errors), all tests, ArchUnit, coverage gates
cd frontend && npm run dev                    # http://localhost:5173, proxies /api
cd frontend && npm run check                  # oxlint --deny-warnings, tsc, Vitest
cd frontend && npm run build
node samples/warehouse-lab-server.mjs         # authorized sample API on 127.0.0.1:8090 (key lab-demo-key)
cd e2e && npx playwright test                 # against a running app (see docs/TESTING.md)
docker compose --profile demo up -d --build   # complete stack, http://localhost:8080
```

Backend tests need Docker (Testcontainers). Do not use `-DskipTests` or `-Djacoco.skip` to call a change verified.

## Architecture rules (enforced by `ArchitectureTest`)

- Package by feature (`com.apiforge.<feature>.{web,service,repository,domain}`). Controllers delegate to services;
  services own data access, transactions and authorization; the API returns DTOs, never entities.
- Only `outbound` opens connections to user-supplied destinations; only `ai` calls the model; only `secrets`
  encrypts and decrypts. Do not add a second HTTP client, model client or cipher.
- No package cycles. Jackson 3 for the application's own JSON; Jackson 2 stays inside the parser adapters.

## Conventions

- Errors are `application/problem+json` with a stable `code` from `ErrorCode`; never return stack traces or
  internal details. Validate input with Bean Validation and reject unknown properties.
- Every project-scoped operation checks ownership through `ProjectAccessService`; another user's resource
  answers exactly like a missing one (404). The frontend is never the security boundary.
- Database changes only as a new Flyway migration (`V14__...` next). Never edit an applied migration; keep
  constraints and foreign keys explicit; update `docs/DATABASE_SCHEMA.md`.
- Frontend: parse every API response with a zod schema (`api/client.ts`); no `any`, no `@ts-ignore`, no lint
  suppressions. Handle loading, empty, error and offline states.
- Match the surrounding code: naming, comment density, test style. Line endings are LF (`.gitattributes`).
- New dependencies need a reason (and a row in the `docs/DECISIONS.md` matrix); prefer what is already there.

## Correctness and AI boundaries

- Test verdicts come only from deterministic checks (status, media type, JSON syntax, schema). A model never
  decides, changes or overrides an outcome. Skipped or inconclusive checks are never reported as passed.
- Model output is validated against strict schemas; suggestions stay "needs review" until a person accepts
  them; nothing from a model is executed or applied automatically. Prompts contain redacted data only.
- Tests must not need a real model, a paid AI service or an external API: use the scripted fakes and the
  local sample server.

## Security constraints

- Outbound requests: keep the guard intact (scheme, URL parser, blocked ranges incl. IPv4 embedded in IPv6,
  validated-address-only DNS, per-hop redirect checks, size and time limits). Any change needs tests in
  `com.apiforge.outbound` first, including a case that fails without the change.
- Never log or return secrets, `Authorization` headers, API keys, passwords or full request bodies; redaction
  is tested (`SecretLoggingIntegrationTest`). Never commit `.env`, keys or real credentials; placeholders only.
- Do not weaken CSP, cookie, CSRF, rate-limit or registration settings to make something work.

## Working rules

1. Read the relevant code and docs before changing anything. Do not invent APIs, files, versions or results.
2. Prefer the smallest change that solves the problem; no drive-by rewrites or speculative features.
3. Review generated code as you would a colleague's pull request: read the whole diff, look for missing
   authorization, unvalidated input, swallowed errors and claims in docs that the code does not support.
4. Run the checks that cover the change (`./mvnw -B verify`, `npm run check`, e2e where a workflow changed)
   and report the real outcome: commands, pass/fail counts, failures with their output. Never report a test
   as passing that was not run, and never skip, weaken or delete a failing test to get green.
5. Keep docs truthful: when behaviour changes, update `README.md`, the matching `docs/` file and
   `docs/PROJECT_STATUS.md`. Documented-but-missing features are defects.
6. Ask before anything destructive or remote: commits, pushes, deployments, deleting data or volumes,
   changing remote resources or publishing anything.
