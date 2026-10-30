# Demo workflow

A safe, reproducible walkthrough of APIForge AI against **Warehouse Lab**, a sample API that ships with this repository only to be tested:

- [`samples/warehouse-lab-openapi.yaml`](../samples/warehouse-lab-openapi.yaml) — the contract (OpenAPI 3.0.3, 7 operations: health, products CRUD, orders), with many constraints so generation has something to work with.
- [`samples/warehouse-lab-server.mjs`](../samples/warehouse-lab-server.mjs) — a dependency-free Node server that implements it with API-key authentication (`X-API-Key`, demo value `lab-demo-key`, which protects nothing but this sample). It contains **one deliberate contract violation** (order quantities above the documented maximum of 999 are accepted) and, in *violations mode*, the further deviations listed in [samples/README.md](../samples/README.md).

Nothing in the demo leaves your machine: the sample is the only destination APIForge may reach in this setup (it blocks private and local addresses otherwise), and AI runs locally if you enable it.

The numbers below are what the workflow produces (verified 2026-10-23, generator version 2). Screenshots in the README were taken while executing exactly these steps.

## 0. Start

**With Docker** (recommended): in `.env` set `APIFORGE_DB_PASSWORD`, `APIFORGE_SECRETS_KEY` (`openssl rand -base64 32`), `APIFORGE_COOKIE_SECURE=false`, `APIFORGE_DEMO_TARGET_ENABLED=true`, and — for the optional AI steps with Ollama on your machine — `APIFORGE_STACK_OLLAMA_URL=http://host.docker.internal:11434`. Then:

```bash
docker compose --profile demo up -d --build
```

Open http://localhost:8080. The sample API is reachable from the backend as **`http://warehouse-lab:8090`**.

**From source**: start PostgreSQL, the backend and the frontend as in the README's development setup, and the sample with `node samples/warehouse-lab-server.mjs`. Open http://localhost:5173. The sample's address is **`http://127.0.0.1:8090`**.

## 1. Account

Choose **Create one**, enter an e-mail, a display name and a password of 12–64 characters. You land on an empty **Overview**.

## 2. Project

Choose **Create your first project**, name it `Warehouse Lab`. The project's Overview shows a setup checklist (0 of 4 steps done).

## 3. Specification

Choose **Import an OpenAPI specification** → **Import specification**, select `samples/warehouse-lab-openapi.yaml`, choose **Check** (dry run: "Ready to import"), then **Import**.

*Expected*: "Imported version 1: 7 endpoints." The explorer lists the endpoints by tag; open **Place an order** (`POST /orders`) to see its request body schema and documented responses.

## 4. Tests

Open **Tests** → **Generate tests**.

*Expected*: 128 tests from version 1 (generator v2), with the scenarios that were not generated and why (for example, no authentication tests for the public `GET /health`). Choose `POST /orders` to see its tests: a valid request plus negative tests, each broken in one place, with the expected status and the reason it exists.

## 5. Environment

Open **Environments** → **New environment** and read the authorization notice. Enter:

| Field | Value |
|---|---|
| Name | `Warehouse Lab (local)` |
| Base URL | `http://warehouse-lab:8090` (Docker) or `http://127.0.0.1:8090` (from source) |
| Execution policy | *Allow destructive* (the sample may be modified; confirm the non-production checkbox) |
| Authentication | *API key in a header* — header `X-API-Key`, variable `API_KEY` |

Save, then add the variable `API_KEY` = `lab-demo-key` with **Secret** checked. *Expected*: the value is shown only as `•••••• (write-only)` from now on.

## 6. Run and inspect the failure

On the project's **Overview**, choose **Run tests** → *All enabled tests of the project* → **Start run**.

*Expected*: the run completes with **127 passed, 1 failed**. Choose the **Failed** filter and open the result *Field 'lines[0].quantity' above maximum 999*:

- **Expected status** — FAIL: expected `400`, actual `201` (the API accepted an invalid order).
- **Body schema** — FAIL at `$.lines[0].quantity`, keyword `maximum`: expected `≤ 999`, actual `1000` (the response echoes the invalid value).
- The documented status, content type and JSON syntax checks pass; the request shows `X-API-Key: «redacted»`.

*Optional, AI*: choose **Explain with AI**. *Expected* (with `qwen2.5:7b`, about 15–30 s): a summary, observed facts marked "found in the data" or "not checkable", likely causes with confidence, debugging steps and proposed changes; nothing is applied automatically.

## 7. Contract violations on purpose, and run comparison

Restart the sample in violations mode:

- Docker: set `APIFORGE_DEMO_VIOLATIONS=1` in `.env`, then `docker compose --profile demo up -d warehouse-lab`.
- From source: stop the server and start it with `WAREHOUSE_LAB_VIOLATIONS=1 node samples/warehouse-lab-server.mjs`.

Run all tests again. *Expected*: **115 passed, 13 failed**. Choose **Compare with previous run**: the newly failing tests are listed as regressions, each linked to both results. The failures are exactly the deviations documented in [samples/README.md](../samples/README.md) (wrong types, a missing required property, a wrong date format, an undocumented status, an unexpected media type, an enum value outside the contract).

Set `APIFORGE_DEMO_VIOLATIONS=0` (or restart without the variable) to return to normal mode.

## 8. Coverage

Open **Coverage**. *Expected* after the first run: 7 of 7 operations with tests, 24 of 25 documented responses targeted, 105 of 105 request constraints and 27 of 27 negative scenario kinds covered, each with its definition; the gaps section lists what is not exercised.

## 9. Optional: AI test suggestions

In **Tests**, choose `POST /orders` → **Suggest tests**. *Expected* (about 30–90 s with `qwen2.5:7b`): suggestions stored as *AI-suggested, needs review*; they cannot run until you **Accept** them, and duplicates of existing tests are discarded or flagged. The Overview counts them under *Tests to review*.

## Clean up

```bash
docker compose --profile demo down       # keeps the data volume
docker compose --profile demo down -v    # also deletes all demo data
```
