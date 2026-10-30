# Sample specifications

| File | Source | License |
|---|---|---|
| `petstore3-openapi.yaml` | Swagger Petstore 3 (`swagger-api/swagger-petstore`, `src/main/resources/openapi.yaml`), copied unmodified on 2026-10-21 | Apache License 2.0 |
| `warehouse-lab-openapi.yaml` | Written for this project (Phase 4) | Same as this repository |
| `warehouse-lab-server.mjs` | Written for this project (Phase 5) | Same as this repository |
| `coverage-lab-openapi.yaml` | Written for this project (Phase 7) | Same as this repository |

Import it from a project's **Specification** page (upload the file or paste its contents). It declares 19 operations.
Its servers point to `petstore3.swagger.io`, a third-party host: importing never contacts it, and you should not run tests
against it unless you are authorized to.

`warehouse-lab-openapi.yaml` (OpenAPI 3.0.3, 7 operations) is built to exercise test generation. It covers:
- integer, number, boolean, string, array and object types;
- inclusive and exclusive bounds, `multipleOf`, string lengths, `pattern` and formats;
- array length and `uniqueItems`;
- enums, `nullable` and `readOnly` fields;
- `allOf`, and `oneOf` with a discriminator;
- `additionalProperties: false` with `minProperties`;
- a required header, a constrained path parameter and a `+json` media type;
- documented error schemas, and API-key security with a public health endpoint.

Generating tests from it produces 128 tests and reports the scenarios it skips, with reasons. Its server (`localhost:8090`)
does not exist yet, and nothing is contacted during import or generation.

`warehouse-lab-server.mjs` is a local, **authorized** target that implements that contract, so tests can be
executed for real: `node samples/warehouse-lab-server.mjs` (Node 22+, no dependencies; listens on
`127.0.0.1:8090` only). Requests other than `GET /health` need the header `X-API-Key: lab-demo-key` (a demo value
that protects nothing; override it with `WAREHOUSE_LAB_API_KEY`).
- **One deliberate contract deviation:** order line quantities above 999 are accepted, so the generated test
  "Field 'lines[0].quantity' above maximum 999" fails.
- **Deliberate contract violations on request** (for trying response validation): start it with
  `WAREHOUSE_LAB_VIOLATIONS=1` (PowerShell: `$env:WAREHOUSE_LAB_VIOLATIONS=1; node samples/warehouse-lab-server.mjs`).
  Then `/health` reports status `DEGRADED` (not in the enum); `GET /products` lists a legacy item first whose `price` is a
  string, `createdAt` is not a date-time and `dimensions.widthCm` is -1; `GET /products/{productId}` omits the required
  `sku`; a PATCH of exactly one property answers `Content-Type: text/plain`; `DELETE /products/42` answers 200 with a
  body (only 204 is documented); an order without `couponCode` gets status `lost`. A run of the generated tests then
  fails 13 tests with exactly 27 failed assertions (these violations plus the quantity deviation), which is how
  Phase 6 was verified.
- **Diagnostic endpoints:** `/lab/echo` (echoes headers, for redaction checks), `/lab/slow?ms=` (timeouts),
  `/lab/malformed` (invalid JSON), `/lab/status/{code}`, `/lab/redirect?to=`, `/lab/redirect-metadata`
  (a redirect to the cloud metadata address, which APIForge refuses) and `/lab/large?kb=` (size limits).

The dev profile trusts exactly this host and port; nothing else on your machine or network is reachable from test
runs.

## Coverage Lab (Phase 7)

`coverage-lab-openapi.yaml` (5 operations) is a small subset of the Warehouse Lab contract with known endpoints and
scenarios, for checking the **Coverage** tab by hand. Run it against `warehouse-lab-server.mjs` (which does not implement
`GET /reports`). Phase 7 was verified with exactly these steps and numbers:

1. Import it and generate tests: **30** tests (health 1; GET, DELETE product 6 each: valid, no/invalid credentials, path
   type, below and at minimum; PATCH 14: those 6 plus missing body, unsupported content type, body type, and stock type,
   below/at minimum, above/at maximum; reports 3).
2. Disable the 3 `GET /reports` tests, PATCH "Unsupported content type" and PATCH "stock at maximum 10000".
3. Edit GET product "Request with invalid credentials" to send the valid request and expect 200 (a duplicate).
4. Run all tests in a read-only environment (PATCH and DELETE are skipped), then run `DELETE /products/{productId}` in an
   environment that allows destructive tests.

| Metric | Expected | Why |
|---|---|---|
| Operations with tests | 4 of 5 | `GET /reports` has only disabled tests |
| Enabled tests (distinct) | 24 | 30 − 5 disabled = 25, the copy counts once |
| Documented responses targeted | 12 of 14 | all but the two of `/reports` |
| Request constraints covered | 15 of 17 | GET 3/3, PATCH 9/11, DELETE 3/3 |
| Negative scenario kinds present | 10 of 12 | missing: content type (PATCH), credentials (`/reports`) |
| Tests executed / passed | 12 of 24 / 12 of 12 | health 1 + GET 5 + DELETE 6; PATCH only skipped (12) |
| Documented responses exercised | 7 of 14 | health 200; GET 200, 401, 404; DELETE 204, 401, 404 |

