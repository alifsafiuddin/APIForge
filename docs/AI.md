# Local AI with Ollama

APIForge can use a **local** language model, served by [Ollama](https://ollama.com), for two optional features:

- **AI test suggestions** — on the Tests tab, for one endpoint: scenarios the existing tests do not cover. They are
  stored as suggestions waiting for review and never run until a person accepts them.
- **Explain failure** — on a failed, errored or inconclusive result: a summary, observed facts (each checked
  against the recorded data), likely causes as hypotheses, debugging steps and proposed changes.

AI is optional. Test generation, execution, contract validation and coverage never call the model and work the same
when Ollama is missing, stopped or misconfigured. No cloud AI service is used.

## 1. Install Ollama

1. Download the installer for your system from <https://ollama.com/download> (Windows, macOS, Linux).
   On Linux: `curl -fsSL https://ollama.com/install.sh | sh`.
2. Optional — choose where models are stored (they are several GB). Set the environment variable `OLLAMA_MODELS`
   **before** starting Ollama, e.g. on Windows `setx OLLAMA_MODELS D:\OllamaModels` and on Linux/macOS
   `export OLLAMA_MODELS=/data/ollama`. On Windows the installer also accepts a target folder:
   `OllamaSetup.exe /DIR="D:\Ollama"`.
3. Start Ollama (the Windows/macOS app starts it; otherwise run `ollama serve`). Check it:
   `curl http://localhost:11434/api/version`.

## 2. Choose a model for your hardware

The model must support Ollama's structured outputs (JSON schema in `format`); all models below do. Sizes are the
default 4-bit downloads.

| Your machine | Suggested model | Download | Notes |
|---|---|---|---|
| 8 GB RAM, no GPU | `llama3.2:3b` | 2.0 GB | Fast; explanations are good, suggestions are often duplicates or invalid and are filtered out (see [AI_EVALUATION.md](AI_EVALUATION.md)) |
| 16 GB RAM, or a GPU with ≥ 6 GB | `qwen2.5:7b` (default) | 4.7 GB | Better suggestions; about 3–4 × slower than the 3B model on the same hardware |
| 32 GB RAM, or a GPU with ≥ 12 GB | `qwen2.5:14b` | 9 GB | Not evaluated here |

A model runs on the GPU when it fits into its memory (NVIDIA with current drivers, Apple Silicon, recent AMD on Linux);
otherwise on the CPU, which is several times slower. Only the measured models are claimed to work well; anything else
should be checked with the evaluation in section 6.

Install the model once: `ollama pull qwen2.5:7b` (or the one you chose).

## 3. Configure APIForge

All settings are environment variables of the backend (defaults in `backend/src/main/resources/application.yml`):

| Variable | Default | Meaning |
|---|---|---|
| `APIFORGE_AI_ENABLED` | `true` | `false` turns the AI features off (the UI says so) |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Where Ollama listens: `http(s)://host:port` without credentials, query or fragment (checked at startup) |
| `APIFORGE_AI_MODEL` | `qwen2.5:7b` | The Ollama model tag |
| `APIFORGE_AI_REQUEST_TIMEOUT` | `120s` | One model call (at most 10 min); a call that takes longer is reported as a timeout and not retried |
| `APIFORGE_AI_MAX_CONCURRENT` | `1` | Model calls at the same time (a local GPU serves one well); further requests wait 2 s, then get "busy" |
| `APIFORGE_AI_CONTEXT_TOKENS` | `8192` | Context window requested from Ollama |

Fixed limits (configuration properties `apiforge.ai.*`): at most 2 attempts per request (only malformed output and
Ollama "busy" answers are retried, once), 10 AI requests per user per minute, 24 000 characters of prompt (larger
inputs are refused before calling the model), 1 500 output tokens, temperature 0.2, at most 5 stored suggestions per
request.

`OLLAMA_BASE_URL` is operator configuration and never user input. Pointing it at another machine sends the
sanitized prompts there; keep it on a host you control.

## 4. Check it

The header shows the model and its state: **Ready**, **Model not installed** (with the `ollama pull` command to run),
**Unavailable** (Ollama not reachable) or **Off**. Click it for details and **Check again**. The same is available as
`GET /api/v1/ai/status?refresh=true`.

## 5. What the model sees, and what it cannot do

| | Suggestions | Explanations |
|---|---|---|
| Sent | the operation's method, path, summary and description; parameters, request body and responses with schemas (references inlined 1–3 levels); the names, categories, scenarios and expected statuses of existing tests; the scenarios the deterministic generator could not build | failed and inconclusive assertions with expected and actual values; test name, rationale and expected statuses; request method, path (no host), test-chosen headers and body; response status, safe headers (content type, length, location…) and a body excerpt; the documented responses and schemas |
| Never sent | environment values, secrets, credentials, base URLs, request values of existing tests | base URL and host, credential headers, cookies, environment variables, values shown as «redacted», source code |

Everything is redacted when results are stored and passes a second sanitizer before it goes into a prompt (sensitive
keys, bearer/basic tokens, JWTs, long opaque strings, sizes). Prompts are neither stored nor logged; the audit trail
records each AI request with model, outcome, attempts, latency, sizes and a SHA-256 of the prompt.

The model's answer must match a strict JSON schema; anything else is reported as invalid output. Suggestions are
request data only (no code, scripts or expressions), validated like a manual edit (path parameters, forbidden
headers such as `Authorization` and `Cookie`, sizes, statuses) and compared with the existing tests: an identical
request with the same expected statuses is discarded unless the model gives a reason, and one that differs in a
single value is flagged as a possible duplicate. Explanations are checked too: every observed fact that cites a
concrete value (JSON path, status code, quoted value) is marked as found or not found in the data the model was given,
and any claim of having read source code is flagged. The model never saw the API's source code, and nothing it
proposes is applied automatically.

## 6. Evaluate a model

`evaluation/ai/run-eval.mjs` runs a fixed dataset of known failures (from the Warehouse Lab sample in violation mode)
and suggestion requests against a running APIForge and reports JSON validity, relevance (expected facts mentioned),
grounding, duplicates and latency:

```bash
# terminal 1: WAREHOUSE_LAB_VIOLATIONS=1 node samples/warehouse-lab-server.mjs
# terminal 2: APIFORGE_AI_MODEL=qwen2.5:7b ./mvnw spring-boot:run   (dev profile, port 8081)
node evaluation/ai/run-eval.mjs --base http://127.0.0.1:8081 --label my-model
```

Measured results are recorded in [AI_EVALUATION.md](AI_EVALUATION.md).

## 7. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| "Unavailable" | Ollama is not running or `OLLAMA_BASE_URL` is wrong. Start Ollama, then **Check again** |
| "Model not installed" | `ollama pull <model>` for the configured `APIFORGE_AI_MODEL` |
| "The model did not answer within the request timeout" | The model is too slow for this hardware (CPU only, or larger than GPU memory). Use a smaller model or raise `APIFORGE_AI_REQUEST_TIMEOUT` |
| "The model is busy…" | Another AI request is running (`APIFORGE_AI_MAX_CONCURRENT`), or Ollama refused it as busy |
| "…not valid JSON" / "…does not match the required structure" | The model ignored the schema twice. Try again, or use a stronger model |
| "Ollama answered 500: model requires more system memory…" | The model does not fit; choose a smaller one |
