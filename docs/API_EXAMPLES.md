# API examples

Real requests and responses, captured on 2026-10-23 from the Docker Compose stack (`docker compose --profile demo up -d`, frontend origin `http://localhost:8080`) with the Warehouse Lab sample API as the test target. Identifiers and timestamps are from that run; long bodies are shortened with `…`, nothing else is edited. The complete endpoint reference is [API_DESIGN.md](API_DESIGN.md).

All `/api/v1/**` endpoints except health, CSRF, registration and sign-in need a session. State-changing requests need the CSRF token: the server sets the `XSRF-TOKEN` cookie, and the client echoes it in the `X-XSRF-TOKEN` header. With `curl`:

```bash
B=http://localhost:8080
curl -s -c jar -b jar -o /dev/null $B/api/v1/auth/csrf                    # receive the XSRF-TOKEN cookie
T=$(awk '/XSRF-TOKEN/ {print $7}' jar)                                    # read it from the cookie jar
curl -s -c jar -b jar -H "X-XSRF-TOKEN: $T" -H 'Content-Type: application/json' \
  -d '{"email":"you@example.test","password":"<12-64 characters>","displayName":"You"}' $B/api/v1/auth/register
T=$(awk '/XSRF-TOKEN/ {print $7}' jar)                                    # the token rotates on sign-in
```

## Register (starts a session)

`POST /api/v1/auth/register` → `201`

```json
{ "id": "01a166d1-a5f0-7bdf-bec1-8666e7cd9eed", "email": "api-62ab2df0@example.test", "displayName": "API Example" }
```

## Create a project

`POST /api/v1/projects` `{"name": "Warehouse Lab", "description": "API example"}` → `201`, `Location: /api/v1/projects/01a166d1-adf7-79be-8ce4-82906cb30d40`

```json
{
  "id": "01a166d1-adf7-79be-8ce4-82906cb30d40",
  "name": "Warehouse Lab",
  "description": "API example",
  "archived": false,
  "archivedAt": null,
  "activeSpecificationVersionId": null,
  "createdAt": "2026-10-23T03:33:00.023655Z",
  "updatedAt": "2026-10-23T03:33:00.023655Z",
  "version": 0
}
```

## Import an OpenAPI document

`POST /api/v1/projects/{id}/specs?activate=true` with `{"content": "<YAML or JSON text>"}` (or a multipart `file`) → `201`

```json
{
  "version": {
    "id": "01a166d1-b60a-7124-b95c-4a6a923c7a5e",
    "versionNumber": 1,
    "title": "Warehouse Lab API",
    "apiVersion": "1.0.0",
    "openapiVersion": "3.0.3",
    "sourceFormat": "YAML",
    "operationCount": 7,
    "warningCount": 0,
    "active": true,
    "contentSha256": "98239953d0bf73c5cc6213c0fde944a5815036d9a341feab0292b783a35a9f74",
    "contentSizeBytes": 10809,
    …
  },
  "messages": []
}
```

## Generate tests

`POST /api/v1/projects/{id}/tests/generate` → `200`

```json
{
  "specificationVersionId": "01a166d1-b60a-7124-b95c-4a6a923c7a5e",
  "specificationVersionNumber": 1,
  "operations": 7,
  "created": 128,
  "updated": 0,
  "unchanged": 0,
  "keptUserEdits": 0,
  "archived": 0,
  "total": 128,
  "generatorVersion": "2",
  "skipped": [
    { "operationKey": "GET /health", "scenario": "Authentication scenarios", "reason": "the operation declares no security requirement" },
    …
  ]
}
```

## Create an environment and a write-only secret

`POST /api/v1/projects/{id}/environments`

```json
{
  "name": "Warehouse Lab (local)",
  "baseUrl": "http://warehouse-lab:8090",
  "timeoutMs": 5000,
  "followRedirects": false,
  "executionPolicy": "ALLOW_DESTRUCTIVE",
  "authorizationConfirmed": true,
  "auth": { "type": "API_KEY_HEADER", "headerName": "X-API-Key", "valueVariable": "API_KEY" }
}
```

→ `201`. Then `PUT …/environments/{envId}/variables/API_KEY` `{"value": "lab-demo-key", "secret": true}` → `200`; the value is encrypted and never returned again (`"value": null, "hasValue": true`).

`http://warehouse-lab:8090` is reachable only because the Compose `demo` profile trusts exactly that sample container. A cloud metadata address is refused:

`POST …/environments` with `"baseUrl": "http://169.254.169.254"` → `400 application/problem+json`

```json
{
  "detail": "The environment settings are invalid.",
  "instance": "/api/v1/projects/01a166d1-adf7-79be-8ce4-82906cb30d40/environments",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR",
  "requestId": "c0ddbe88-a442-4b60-ac6f-00fd22c87f91",
  "errors": [
    { "field": "baseUrl", "message": "The address is a link-local or cloud metadata address and is not allowed." }
  ]
}
```

## Run the tests

`POST /api/v1/projects/{id}/runs` `{"environmentId": "01a166d1-cee9-7070-8285-77cc466d286f"}` → `202`, `Location: …/runs/01a166d1-df00-7e94-8129-056f0430234d`

```json
{
  "id": "01a166d1-df00-7e94-8129-056f0430234d",
  "kind": "TEST_RUN",
  "status": "QUEUED",
  "environmentName": "Warehouse Lab (local)",
  "baseUrl": "http://warehouse-lab:8090",
  "counts": { "total": 128, "passed": 0, "failed": 0, "inconclusive": 0, "error": 0, "skipped": 0 },
  …
}
```

Poll `GET …/runs/{runId}` until `status` is `COMPLETED`; this run ended with `"counts": {"total": 128, "passed": 127, "failed": 1, "inconclusive": 0, "error": 0, "skipped": 0}`.

## Inspect the contract failure

`GET …/runs/{runId}/results?outcome=FAILED` → `200`

```json
{
  "content": [
    {
      "id": "01a166d1-e0c0-771a-a2f6-aecff9032214",
      "testName": "Field 'lines[0].quantity' above maximum 999",
      "operationKey": "POST /orders",
      "outcome": "FAILED",
      "responseStatus": 201,
      "failedChecks": 2,
      "skippedChecks": 0,
      "firstFailure": "The test expects 400, but the response is 201.",
      …
    }
  ],
  "page": { "number": 0, "size": 5, "totalElements": 1, "totalPages": 1 }
}
```

`GET …/runs/{runId}/results/{resultId}` → `200` (shortened to the checks, request and response):

```json
{
  "outcome": "FAILED",
  "checks": [
    { "name": "STATUS", "result": "FAIL", "expected": "400", "actual": "201", "message": "The test expects 400, but the response is 201." },
    { "name": "STATUS_DOCUMENTED", "result": "PASS", "message": "Status 201 is documented." },
    { "name": "CONTENT_TYPE", "result": "PASS", "message": "Content-Type 'application/json' matches the documented 'application/json'." },
    { "name": "JSON_SYNTAX", "result": "PASS", "message": "The body is well-formed JSON." },
    { "name": "BODY_SCHEMA", "result": "FAIL", "path": "$.lines[0].quantity", "keyword": "maximum", "expected": "≤ 999", "actual": "1000", "message": "$.lines[0].quantity must be ≤ 999." }
  ],
  "request": {
    "method": "POST",
    "url": "http://warehouse-lab:8090/orders",
    "headers": { "X-API-Key": "«redacted»", "X-APIForge-Request-Id": "e7d8db97-c1d2-4e80-944d-1dec80ca4f1c" },
    …
  },
  "response": {
    "status": 201,
    "body": "{\"lines\":[{\"quantity\":1000,\"productId\":1}],\"notes\":\"sample\",\"express\":true,…,\"status\":\"accepted\"}",
    …
  },
  …
}
```

The sample API accepts an order quantity above the documented maximum and echoes it: the status check and the response schema check both report it, with the JSON path, expected and actual value. The API key is redacted in everything stored and returned.

## Coverage

`GET /api/v1/projects/{id}/coverage` → `200`; metrics after the run above (`[id, value, total]`):

```text
operations              7
operationsWithTests     7 of 7
tests                 128
responsesTargeted      24 of 25
constraintsCovered    105 of 105
branchesExercised       1 of 2
negativeKinds          27 of 27
executed              128 of 128
passRate              127 of 128
```

## AI status

`GET /api/v1/ai/status` → `200`

```json
{
  "enabled": true,
  "state": "READY",
  "model": "qwen2.5:7b",
  "server": "http://host.docker.internal:11434",
  "installedModels": ["llama3.2:3b", "qwen2.5:7b"],
  "message": "Ollama is reachable and the model is installed.",
  "latencyMs": 19,
  "checkedAt": "2026-10-23T03:33:33.093265Z"
}
```

## Errors

Every error is `application/problem+json` with a stable `code` and the `requestId` (also in the `X-Request-Id` header):

`POST /api/v1/projects` `{"name": "", "description": "<2001 characters>"}` → `400`

```json
{
  "detail": "One or more fields are invalid.",
  "instance": "/api/v1/projects",
  "status": 400,
  "title": "Bad Request",
  "code": "VALIDATION_ERROR",
  "requestId": "a34b7433-74df-40a7-95d9-95c867d98059",
  "errors": [
    { "field": "description", "message": "size must be between 0 and 2000" },
    { "field": "name", "message": "must not be blank" }
  ]
}
```

`GET …/runs?status=FINISHED` → `400`

```json
{
  "detail": "Failed to convert 'status' with value: 'FINISHED'",
  "instance": "/api/v1/projects/01a166d1-adf7-79be-8ce4-82906cb30d40/runs",
  "status": 400,
  "title": "Bad Request",
  "code": "MALFORMED_REQUEST",
  "requestId": "87b633f7-9248-4c91-9a48-346f7a490ca3"
}
```
