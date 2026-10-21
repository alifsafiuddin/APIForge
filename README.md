# APIForge AI

A full-stack developer tool for **contract-driven API testing**. You import an OpenAPI 3.x specification, explore its endpoints, and generate deterministic test scenarios from its schemas. APIForge AI then runs them against an API you are authorized to test, validates every response against the documented contract, tracks specification coverage, and can ask a **locally hosted** LLM (Ollama) to explain failures.

> **Status: Phase 7 (coverage and missing-scenario analysis) complete.**
> What runs today:
> - Registration and sign-in (sessions, CSRF, lockout, rate limiting, audit trail) and private API projects.
> - **OpenAPI 3.0/3.1 import** with immutable versions and a **specification explorer**.
> - **Deterministic test generation** with a review and curation workspace.
> - **Environments**: base URL, timeouts, redirects, execution policy, authentication (bearer, basic, API key), default headers, variables; secret variables are AES-256-GCM encrypted and write-only.
> - **Specification coverage**: operations with and without tests, documented responses targeted and exercised, request constraints, schema branches and negative scenario kinds covered — each metric defined on the page, endpoint coverage kept apart from the pass rate, duplicated tests counted once. Gaps (untested endpoints, unexercised responses, missing negative scenarios), filters, per-operation drill-down, and changes between specification versions. This is specification coverage, never code coverage.
> - **Contract validation and reporting**: every response is checked against the active specification — documented status, media type, JSON syntax and the response schema (types, required fields, nested objects, arrays, enums, constraints, formats) — with a JSON path, expected and actual value per finding. Skipped and inconclusive checks are shown as such, never as passes. Runs can be filtered by outcome, results show assertions, the response body with findings marked, and timing, and a run can be compared with an earlier one.
> - **Secure execution**: test runs (asynchronous, cancellable, per-user limits) and a console for single requests, against an API you are authorized to test. Private, loopback, link-local, multicast and cloud metadata destinations are blocked, DNS is validated before connecting (rebinding-safe), every redirect is re-checked, and secrets are redacted from everything that is stored, returned or logged.
>
> Contract validation of response bodies, coverage and AI are **not implemented yet**. See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md).

## Quick start

### Prerequisites

| Tool | Version | Check |
|---|---|---|
| JDK | 21 | `java -version` |
| Node.js | 24 LTS (minimum 22.22) | `node -v` |
| Docker Desktop / Engine with Compose v2 | current | `docker compose version` |

Maven does not need to be installed: the backend ships the Maven Wrapper (`mvnw`, `mvnw.cmd`).

### 1. Configure local settings

```bash
cp .env.example .env              # PowerShell: Copy-Item .env.example .env
```

Edit `.env` and set `APIFORGE_DB_PASSWORD` to a password of your choice. `.env` is git-ignored. Docker Compose and the backend dev profile both read it.

### 2. Start PostgreSQL

```bash
docker compose up -d --wait postgres
```

This starts PostgreSQL 18 on `127.0.0.1:5432` (change `APIFORGE_DB_PORT` in `.env` if that port is taken). Data persists in the `apiforge_pgdata` Docker volume.

### 3. Start the backend (http://localhost:8081)

```bash
cd backend
./mvnw spring-boot:run            # Windows: .\mvnw.cmd spring-boot:run
```

On startup, Flyway applies the database migrations. The `dev` profile is active and reads `../.env`.

### 4. Start the frontend (http://localhost:5173)

```bash
cd frontend
npm ci
npm run dev
```

Open http://localhost:5173, choose **Create one** to register (password: 12–64 characters, not a common password), and you land on the Overview with your projects and live system health.

To try the explorer: create a project, open it, choose **Import specification** and upload [`samples/petstore3-openapi.yaml`](samples/) (the official Swagger Petstore 3 description, 19 endpoints). Importing never contacts the hosts the document mentions.

To try test generation: import [`samples/warehouse-lab-openapi.yaml`](samples/), a constraint-rich sample with 7 endpoints, then open the project's **Tests** tab and choose **Generate tests**. The report lists what was created and every scenario that was skipped, with the reason. Pick an endpoint to see its tests; open one to see why it exists, what it sends and what it expects. You can edit, disable or move it to another suite. **Regenerate tests** creates no duplicates and leaves your edits in place.

To execute tests: start the local sample API with `node samples/warehouse-lab-server.mjs` (it listens on `127.0.0.1:8090`, the only destination the dev profile trusts). On **Environments**, create an environment with base URL `http://127.0.0.1:8090`, authentication *API key in a header* (`X-API-Key`, variable `API_KEY`), and add the secret variable `API_KEY` = `lab-demo-key`. Then use **Run** on the Tests tab, follow the run under **Runs**, or send single requests from the **Console** (for example `/lab/slow`, `/lab/malformed` or `/lab/redirect-metadata` to see timeouts, malformed responses and blocked redirects).

To see contract validation: open a run under **Runs**. Filter it by outcome, open a result to see each assertion with expected and actual values, the response body with failing JSON paths marked, and the timing, or choose **Compare with previous run**. Restart the sample with `WAREHOUSE_LAB_VIOLATIONS=1` and run again to see status, media-type and schema violations reported ([samples/README.md](samples/README.md) lists them).

To see coverage: open the project's **Coverage** tab. [samples/README.md](samples/README.md) describes a small specification (`coverage-lab-openapi.yaml`) with the numbers you should see after a few documented steps.

The Vite dev server proxies `/api` and `/actuator` to the backend, so the browser only talks to one origin and the session cookie stays first-party.

## Commands

| Where | Command | Purpose |
|---|---|---|
| `backend/` | `./mvnw verify` | Compile (warnings are errors), run unit and integration tests (Testcontainers starts a throwaway PostgreSQL, so Docker must be running), enforce dependency convergence, write the JaCoCo report to `target/site/jacoco/` |
| `backend/` | `./mvnw spring-boot:run` | Run the backend with the `dev` profile |
| `backend/` | `./mvnw package` then `java -jar target/apiforge-backend-*.jar --spring.profiles.active=prod` | Build and run the production jar (requires `APIFORGE_DB_URL`, `APIFORGE_DB_USER`, `APIFORGE_DB_PASSWORD`) |
| `frontend/` | `npm run dev` | Dev server with hot reload |
| `frontend/` | `npm run lint` / `npm run typecheck` / `npm test` | Lint (oxlint), type-check (tsc), unit tests (Vitest + Testing Library + MSW) |
| `frontend/` | `npm run check` | All three of the above |
| `frontend/` | `npm run test:coverage` | Tests with a coverage report in `coverage/` |
| `frontend/` | `npm run build` / `npm run preview` | Production build to `dist/` and a local preview on :4173 |
| root | `docker compose up -d --wait postgres` / `docker compose down` | Start or stop PostgreSQL (`down -v` also deletes the data volume) |

## Endpoints available now

All `/api/v1/**` endpoints require a signed-in session unless marked public. State-changing requests need the CSRF header (`X-XSRF-TOKEN`, echoing the `XSRF-TOKEN` cookie). Full details: [docs/API_DESIGN.md](docs/API_DESIGN.md).

| Endpoint | Description |
|---|---|
| `GET /actuator/health`, `/health/liveness`, `/health/readiness` | Public. Health with component statuses; `503` when down |
| `GET /actuator/info`, `GET /api/v1/system/info` | Public. Build and version information |
| `GET /api/v1/auth/csrf` | Public. Issues the CSRF cookie |
| `POST /api/v1/auth/register`, `POST /api/v1/auth/login` | Public. Create an account / sign in (starts a session) |
| `POST /api/v1/auth/logout`, `GET /api/v1/auth/me` | Sign out / current user |
| `GET, POST /api/v1/projects`; `GET, PATCH, DELETE /api/v1/projects/{id}` | Your projects (paged, searchable, optimistic locking on update) |
| `POST /api/v1/projects/{id}/specs` (multipart `file` or JSON `{content}`), `POST …/specs/validate` | Import an OpenAPI 3.0/3.1 document as a new version / dry-run check |
| `GET /api/v1/projects/{id}/specs[/{versionId}[/raw\|/schemas\|/operations[/{operationId}]]]`, `POST …/{versionId}/activate` | Versions, original document, component schemas, endpoint index and detail; activate a version |
| `POST /api/v1/projects/{id}/tests/generate`, `GET …/tests/generation-plan?operationKey=` | Generate tests from the active version (report with skipped scenarios and reasons) / preview one operation |
| `GET /api/v1/projects/{id}/tests[/summary\|/{testId}]`, `PUT, PATCH …/tests/{testId}`, `POST …/tests/bulk`, `POST …/tests/{testId}/review` | List and filter tests, counts per operation, detail; edit, enable/disable or move; bulk changes; approve/reject |
| `GET, POST /api/v1/projects/{id}/test-suites`, `PATCH, DELETE …/test-suites/{suiteId}` | Suites (only empty custom suites can be deleted) |
| `GET, POST /api/v1/projects/{id}/environments`; `GET, PUT, DELETE …/environments/{envId}`; `PUT, DELETE …/variables/{name}` | Environments and variables (secrets write-only) |
| `POST /api/v1/projects/{id}/runs`, `POST …/runs/{runId}/cancel` | Start a run of tests (202, poll the run) / cancel it |
| `POST /api/v1/projects/{id}/executions` | Send one request now (console) |
| `GET /api/v1/projects/{id}/runs[/{runId}[/results[/{resultId}]]]` | Run history and results (redacted request and response, contract assertions); `?outcome=` filters results |
| `GET /api/v1/projects/{id}/runs/{runId}/comparison[?baseline=]` | Outcome changes against the previous (or a chosen) run |
| `GET /api/v1/projects/{id}/coverage[?environmentId=]`, `…/coverage/operation?key=`, `…/coverage/changes` | Specification coverage, one operation's coverage, changes between versions |

Every response carries an `X-Request-Id` header. Errors use `application/problem+json` with a stable `code`.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `APIFORGE_DB_NAME` / `APIFORGE_DB_USER` / `APIFORGE_DB_PASSWORD` | `apiforge` / `apiforge` / *(required)* | Database credentials (Compose and backend) |
| `APIFORGE_DB_PORT` | `5432` | Host port for PostgreSQL |
| `APIFORGE_DB_URL` | built from the values above | Full JDBC URL (required in `prod`) |
| `APIFORGE_SERVER_PORT` | `8081` | Backend port |
| `APIFORGE_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` in `dev`, none otherwise | Explicit browser origins allowed to call `/api/**` cross-origin (wildcards are rejected at startup) |
| `APIFORGE_AUTH_REGISTRATION_ENABLED` | `true` | Allow self-registration |
| `APIFORGE_COOKIE_SECURE` | `false` (`true` in `prod`) | `Secure` flag on session and CSRF cookies; required over HTTPS |
| `APIFORGE_SESSION_IDLE_TIMEOUT` / `APIFORGE_SESSION_ABSOLUTE_TIMEOUT` | `30m` / `12h` | Session lifetime |
| `APIFORGE_SPEC_MAX_SIZE` | `5MB` | Maximum OpenAPI document size |
| `APIFORGE_SECRETS_KEY` | dev-only key in `dev`; *(required)* otherwise | Base64 of 32 random bytes; encrypts secret variables (AES-256-GCM). The public dev key is refused by `prod` |
| `APIFORGE_OUTBOUND_CONNECT_TIMEOUT` / `APIFORGE_OUTBOUND_TOTAL_DEADLINE` | `5s` / `30s` | Connect timeout and whole-request deadline for test execution (bounded: 30 s / 3 min) |
| `APIFORGE_OUTBOUND_ALLOW_TRUSTED_IN_PROD` | `false` | Acknowledgement needed to use trusted local targets with the `prod` profile |
| `APIFORGE_BACKEND_URL` (frontend) | `http://localhost:8081` | Where the Vite dev server proxies API calls |
| `VITE_API_BASE_URL` (frontend) | empty | Set only to call the backend cross-origin instead of through the proxy |

See [.env.example](.env.example) and [frontend/.env.example](frontend/.env.example). No secrets are committed.

## Troubleshooting

- **`node -v` shows an old version** (for example v18): another Node installation (Laragon, nvm) comes earlier on `PATH`. Put the Node 24 directory first, and restart your terminal or editor after installing.
- **`docker` not found right after installing Docker Desktop**: restart the terminal or editor so it picks up the new `PATH`, and make sure Docker Desktop is running.
- **"Invalid e-mail or password" although the password is right**: after 5 failed attempts sign-in is locked for 15 minutes. Wait, or locally run `docker compose exec postgres psql -U apiforge -d apiforge -c "update users set locked_until = null, failed_login_count = 0 where email = '<you>'"`.
- **"External reference … is not supported" when importing**: APIForge never fetches other files or URLs. Bundle multi-file specifications into one document first (for example `npx @redocly/cli bundle openapi.yaml -o bundled.yaml`).
- **Signed out after restarting the backend**: sessions are kept in memory in this version, so a restart ends them.
- **Backend fails with a connection error**: PostgreSQL is not running or `.env` has a different password from the one the volume was created with. Run `docker compose down -v` to reset the local database (this deletes its data).
- **Port already in use**: change `APIFORGE_DB_PORT` or `APIFORGE_SERVER_PORT` in `.env`. The frontend dev server uses port 5173 strictly.

## Project layout

```
backend/     Spring Boot 4.1 (Java 21, Maven Wrapper), package-by-feature: auth/, audit/, project/, spec/,
             environment/, testcase/, execution/, system/, common/, config/ (+ Flyway migrations V1–V7)
samples/     Sample OpenAPI documents to import (with source and license)
frontend/    React 19 + TypeScript 6 + Vite 8 + Tailwind CSS 4 + shadcn/ui + TanStack Query + React Router
docs/        Architecture, schema, API design, security, testing, deployment, decisions, status
.github/     CI workflow and Dependabot
```

## Documentation

| Document | Contents |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | System context, modules, key flows, outcome semantics, frontend routes |
| [DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md) | ER diagram, tables, constraints, migration plan |
| [API_DESIGN.md](docs/API_DESIGN.md) | REST conventions, error format, endpoint inventory |
| [SECURITY.md](docs/SECURITY.md) | Threat model, SSRF controls, secrets, AI safety |
| [TESTING.md](docs/TESTING.md) | Test strategy and CI gates |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Prerequisites, Compose topology, configuration, CI/CD |
| [DECISIONS.md](docs/DECISIONS.md) | ADRs and the verified dependency compatibility matrix |
| [PROJECT_STATUS.md](docs/PROJECT_STATUS.md) | Current phase, milestone checklist, verification results |

## Responsible use

APIForge AI will send real HTTP requests once execution features land. Only point it at systems you own or are explicitly authorized to test.
