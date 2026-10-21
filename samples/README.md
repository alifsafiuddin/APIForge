# Sample specifications

| File | Source | License |
|---|---|---|
| `petstore3-openapi.yaml` | Swagger Petstore 3 (`swagger-api/swagger-petstore`, `src/main/resources/openapi.yaml`), copied unmodified on 2026-10-21 | Apache License 2.0 |
| `warehouse-lab-openapi.yaml` | Written for this project (Phase 4) | Same as this repository |
| `warehouse-lab-server.mjs` | Written for this project (Phase 5) | Same as this repository |

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
- **Diagnostic endpoints:** `/lab/echo` (echoes headers, for redaction checks), `/lab/slow?ms=` (timeouts),
  `/lab/malformed` (invalid JSON), `/lab/status/{code}`, `/lab/redirect?to=`, `/lab/redirect-metadata`
  (a redirect to the cloud metadata address, which APIForge refuses) and `/lab/large?kb=` (size limits).

The dev profile trusts exactly this host and port; nothing else on your machine or network is reachable from test
runs.

