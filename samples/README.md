# Sample specifications

| File | Source | License |
|---|---|---|
| `petstore3-openapi.yaml` | Swagger Petstore 3 (`swagger-api/swagger-petstore`, `src/main/resources/openapi.yaml`), copied unmodified on 2026-10-21 | Apache License 2.0 |
| `warehouse-lab-openapi.yaml` | Written for this project (Phase 4) | Same as this repository |

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
