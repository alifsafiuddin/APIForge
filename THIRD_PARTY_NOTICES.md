# Third-party notices

APIForge AI is released under the [MIT License](LICENSE). It depends on third-party software and bundles a few third-party assets, each under its own license. All of them are compatible with distributing APIForge AI under the MIT License; this file lists them and what they require.

Inventory taken on 2026-10-23 from the dependency tree of this repository (backend: CycloneDX SBOM of the runtime dependencies; frontend: the packages that are part of the production build, from `package-lock.json`). Regenerate it when dependencies change:

```bash
cd backend && ./mvnw org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeBom -DincludeTestScope=false -DoutputFormat=json -DoutputName=bom   # licenses in target/bom.json
```

## Backend (132 runtime components)

| License | Components | Notes |
|---|---|---|
| Apache-2.0 | 109 | Spring Boot, Spring Framework, Spring Security, Spring AI, Hibernate ORM 7, Jackson, Flyway, Tomcat, Swagger Parser, Apache HttpClient 5, RgxGen, Micrometer, … Keep their NOTICE files when redistributing the built jar or image (they are inside the jar) |
| BSD-2-Clause / BSD-3-Clause / "BSD licence" | 11 | PostgreSQL JDBC driver, ANTLR runtimes, StringTemplate (declared BSD-4-Clause in its metadata: attribution is required), HdrHistogram (also CC0), … |
| MIT, MIT-0 | 5 | SLF4J, Reactive Streams, … |
| EPL-2.0 or LGPL-2.1 (dual) | 2 | Logback (used unmodified under EPL-2.0) |
| EPL-2.0 or GPL-2.0 with Classpath Exception (dual) | 2 | Jakarta Annotations and Transactions APIs (used unmodified) |
| EPL-2.0 (also BSD-3-Clause / EPL-2.0) | 2 | AspectJ weaver, … (used unmodified) |

Test-only dependencies (JUnit 5 — EPL-2.0, AssertJ, ArchUnit, Testcontainers — Apache-2.0/MIT) are not distributed.

## Frontend (98 packages in the production build)

| License | Packages | Notes |
|---|---|---|
| MIT | 93 | React, React DOM, React Router, TanStack Query, Radix UI, zod, react-hook-form, sonner, cn, tw-animate-css, … |
| ISC | 1 | lucide-react (icons) |
| Apache-2.0, BSD-3-Clause, 0BSD | 3 | class-variance-authority, fast-uri, tslib |
| **OFL-1.1** | 1 | **Geist** variable font (`@fontsource-variable/geist`), © 2024 The Geist Project Authors. The font files are part of the built app; the license text ships with every build at `/licenses/geist-font-OFL-1.1.txt` ([source](frontend/public/licenses/geist-font-OFL-1.1.txt)). The font is used unmodified and not sold on its own |

Development-only packages (Vite, TypeScript, Vitest, Testing Library, MSW, oxlint, Playwright) are not distributed.

## Bundled samples

| File | Source | License |
|---|---|---|
| `samples/petstore3-openapi.yaml` (and the copy in backend test fixtures) | Swagger Petstore 3, `swagger-api/swagger-petstore` | Apache-2.0 |
| `samples/warehouse-lab-openapi.yaml`, `samples/warehouse-lab-server.mjs`, `samples/coverage-lab-openapi.yaml` | written for this project | MIT (this repository) |

## Container images

The Dockerfiles build on upstream images — `eclipse-temurin` (OpenJDK: GPL-2.0 with Classpath Exception), `nginxinc/nginx-unprivileged` (nginx: BSD-2-Clause), Alpine Linux packages (various, including GPL-licensed BusyBox) — and Compose uses `postgres`, `ollama/ollama`, `caddy` and `node` images. Their components remain under their own licenses; images published from this repository include those layers unchanged, and the upstream source is available from the respective projects.

## AI models

No model is bundled. Ollama downloads models chosen by the operator (the default `qwen2.5:7b` and the alternative `llama3.2:3b` have their own licenses and acceptable-use terms, published with the models).
