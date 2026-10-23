# Portfolio summary

## Project summary

**APIForge AI** is a full-stack platform for contract-driven REST API testing. It imports an OpenAPI 3.0/3.1 specification, generates deterministic test scenarios from its schemas and constraints, executes them against an API the user is authorized to test, and validates every response against the documented contract — reporting each finding with its JSON path, expected and actual value. Execution history, run comparison and specification coverage show what is tested and what changed. An optional, locally hosted language model (Ollama, through Spring AI) suggests additional tests, which a person must approve before they can run, and explains failed assertions while keeping data-checked facts apart from hypotheses. The application is built with Java 21, Spring Boot 4, React 19, TypeScript and PostgreSQL, packaged as Docker images with a Compose stack, and tested with JUnit 5, Testcontainers, Vitest and Playwright.

## Resume entry

**APIForge AI — AI-Assisted REST API Testing Platform** · Java 21, Spring Boot 4, React 19, TypeScript, PostgreSQL, Docker · [github.com/alifsafiuddin/APIForge](https://github.com/alifsafiuddin/APIForge)

- Developed a full-stack API testing platform using Java, Spring Boot, React, TypeScript and PostgreSQL that imports OpenAPI 3.0/3.1 specifications, generates test scenarios, executes HTTP requests and validates responses against the documented contract, reporting each violation with its JSON path, expected and actual value.
- Implemented deterministic schema-based test generation (128 tests for a 7-operation sample API, covering all 105 request constraints identified in its specification), response contract validation, execution history with run comparison, and API specification coverage analysis; covered by 868 backend tests (JUnit 5, Testcontainers PostgreSQL; 94% line coverage), 125 frontend tests and 12 Playwright end-to-end journeys.
- Integrated a locally hosted language model through Ollama and Spring AI to suggest additional test scenarios (held for human review) and explain failed assertions using JSON-schema structured output; both evaluated models returned valid structured output in 10 of 10 evaluation cases.
- Secured test execution against server-side request forgery (private and metadata addresses, DNS rebinding, redirects) and protected stored credentials with AES-256-GCM encryption and redaction, with dedicated security tests including 168 outbound-request tests.
- Containerized the application with Docker (non-root, read-only containers, health-checked Docker Compose startup, HTTPS via Caddy) and configured GitHub Actions workflows for build and tests, end-to-end tests on the containerized stack, and dependency and secret scanning.

Shorter variant (three bullets):

- Built a full-stack OpenAPI testing platform (Java 21, Spring Boot 4, React 19, TypeScript, PostgreSQL) that generates schema-based tests, executes them and validates responses against the contract with JSON-path-level findings, history, run comparison and coverage analysis.
- Added optional local-LLM assistance (Ollama, Spring AI, structured output) for test suggestions under human review and failure explanations with data-checked facts.
- Verified with 868 backend, 125 frontend and 12 end-to-end tests; containerized with Docker and Compose, with GitHub Actions workflows for tests, images and security scans.

## Where each number comes from

| Claim | Source (all executed, see the linked reports) |
|---|---|
| 868 backend tests, 94.0% line / 80.1% branch coverage | Clean-checkout `./mvnw verify` (PROJECT_STATUS.md, Phase 10/11; QA_REPORT.md) |
| 125 frontend tests | Clean-checkout `npm run check` |
| 12 end-to-end journeys | `e2e/` Playwright suite, run with and without Ollama (PROJECT_STATUS.md) |
| 128 tests, 105 of 105 constraints | Demo workflow on `samples/warehouse-lab-openapi.yaml` (docs/DEMO.md, docs/API_EXAMPLES.md) |
| Contract violations reported exactly | Violations mode of the sample: 13 failed tests, 27 failed assertions, equal to the server's documented deviations (Phase 6 completion check) |
| 10 of 10 valid structured outputs | docs/AI_EVALUATION.md (`qwen2.5:7b` and `llama3.2:3b`) |
| 168 outbound-request tests | `AddressClassifierTest` (54), `UrlGuardTest` (49), `DestinationPolicyTest` (44), `OutboundHttpClientTest` (21) |

Keep the wording to these verified facts. Before citing CI results, push the repository and confirm that the GitHub Actions workflows pass there: so far they have been validated with `actionlint`, and the same steps were executed locally.
