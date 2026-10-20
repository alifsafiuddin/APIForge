# APIForge AI

A full-stack developer tool for **contract-driven API testing**. You import an OpenAPI 3.x specification, explore its endpoints, and generate deterministic test scenarios from its schemas. APIForge AI then runs them against an API you are authorized to test, validates every response against the documented contract, tracks specification coverage, and can ask a **locally hosted** LLM (Ollama) to explain failures.

> **Status: Phase 1 (runnable foundation) complete.**
> What runs today: the Spring Boot backend with PostgreSQL, Flyway migrations, health checks, a documented error format and request correlation IDs, plus the React application shell with a live system-status view.
> API testing features (projects, spec import, test generation, execution, validation, coverage and AI) are **not implemented yet**. See [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md).

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

Open http://localhost:5173. The Overview page shows live backend and database health. The Vite dev server proxies `/api` and `/actuator` to the backend, so the browser only talks to one origin.

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

| Endpoint | Description |
|---|---|
| `GET /actuator/health` | Overall health with component statuses (database, disk). Returns `503` when a component is down. Details are not exposed |
| `GET /actuator/health/liveness`, `/readiness` | Kubernetes-style probes; readiness includes the database |
| `GET /actuator/info` | Build name and version |
| `GET /api/v1/system/info` | Application name, version, API version, server time |

Every response carries an `X-Request-Id` header (a client-supplied id is reused if it is 8–64 safe characters). Errors use `application/problem+json`, as described in [docs/API_DESIGN.md](docs/API_DESIGN.md#1-conventions).

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `APIFORGE_DB_NAME` / `APIFORGE_DB_USER` / `APIFORGE_DB_PASSWORD` | `apiforge` / `apiforge` / *(required)* | Database credentials (Compose and backend) |
| `APIFORGE_DB_PORT` | `5432` | Host port for PostgreSQL |
| `APIFORGE_DB_URL` | built from the values above | Full JDBC URL (required in `prod`) |
| `APIFORGE_SERVER_PORT` | `8081` | Backend port |
| `APIFORGE_CORS_ALLOWED_ORIGINS` | `http://localhost:5173` in `dev`, none otherwise | Explicit browser origins allowed to call `/api/**` cross-origin (wildcards are rejected at startup) |
| `APIFORGE_BACKEND_URL` (frontend) | `http://localhost:8081` | Where the Vite dev server proxies API calls |
| `VITE_API_BASE_URL` (frontend) | empty | Set only to call the backend cross-origin instead of through the proxy |

See [.env.example](.env.example) and [frontend/.env.example](frontend/.env.example). No secrets are committed.

## Troubleshooting

- **`node -v` shows an old version** (for example v18): another Node installation (Laragon, nvm) comes earlier on `PATH`. Put the Node 24 directory first, and restart your terminal or editor after installing.
- **`docker` not found right after installing Docker Desktop**: restart the terminal or editor so it picks up the new `PATH`, and make sure Docker Desktop is running.
- **Backend fails with a connection error**: PostgreSQL is not running or `.env` has a different password from the one the volume was created with. Run `docker compose down -v` to reset the local database (this deletes its data).
- **Port already in use**: change `APIFORGE_DB_PORT` or `APIFORGE_SERVER_PORT` in `.env`. The frontend dev server uses port 5173 strictly.

## Project layout

```
backend/     Spring Boot 4.1 (Java 21, Maven Wrapper): common/ (errors, correlation), config/, system/
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
