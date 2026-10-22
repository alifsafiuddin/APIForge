# AI evaluation

Measured on 2026-10-22 with the dataset and script in [`evaluation/ai/`](../evaluation/ai/); raw results (every
explanation and suggestion) are in [`evaluation/ai/results/`](../evaluation/ai/results/). Nothing below is estimated:
each number comes from those runs. Two models were evaluated; others are untested.

**Machine**: Windows 11, AMD Ryzen 7 5800H (8 cores), 16 GB RAM, NVIDIA GeForce RTX 3060 Laptop GPU (6 GB), Ollama
0.40.7. Both models fit in GPU memory. Prompts `ai-explain-1` and `ai-suggest-1`, temperature 0.2, context 8 192
tokens, at most 1 500 output tokens.

## Dataset

- **Explanations — 10 known failures** with a known cause and the facts a useful explanation must mention
  ([`dataset.json`](../evaluation/ai/dataset.json)): 7 contract violations from the Warehouse Lab sample in violation
  mode (wrong enum value, legacy item with wrong types/format/minimum, missing required property, wrong content type,
  undocumented 200 instead of 204, enum violation in an order, quantity above maximum), a read timeout, a malformed JSON
  body and an unexpected 503.
- **Suggestions — the 7 operations** of `samples/warehouse-lab-openapi.yaml`, after deterministic generation (128
  tests), so the model has to find what those tests miss.

## Measures

| Measure | How it is computed |
|---|---|
| Valid structured output | The answer parsed as JSON and matched the strict schema (after at most one retry) |
| Relevance | Share of a case's "must mention" groups that appear in the explanation (each group lists alternatives, e.g. `["enum", "UP", "allowed"]`) |
| Grounding | Observed facts that cite a concrete value (JSON path, status code, quoted value) and whose every cited value appears in the data the model was given; facts citing nothing concrete are "not checkable" |
| Code claims | Explanations flagged for claiming or implying they read source code |
| Duplicates | Suggestions discarded as the same request and statuses as an existing test or another suggestion; stored suggestions flagged as differing from an existing test in one value |
| Invalid | Suggestions refused by the same validation as a manual edit (e.g. missing path parameter, `Content-Type` header) |
| Latency | Wall time of the API call seen by the client, with the model already loaded |

## Results

### Failure explanations (10 cases)

| | `qwen2.5:7b` | `llama3.2:3b` |
|---|---|---|
| Valid structured output | **10 / 10** | **10 / 10** |
| Fully relevant (all expected facts mentioned) | 9 / 10 | 9 / 10 |
| Mean relevance | 0.97 | 0.97 |
| Observed facts | 28 | 93 |
| … citing concrete values (checkable) | 9 | 34 |
| … of those found in the data | 9 / 9 | 34 / 34 |
| Code claims flagged | 1 (false positive, see below) | 1 (false positive, see below) |
| Latency p50 / p95 / max | 16.1 s / 25.5 s / 25.5 s | 5.3 s / 7.9 s / 7.9 s |

The missed facts: `qwen2.5:7b` said "status 'DEGRADED'" without naming the `$.status` field in the health case;
`llama3.2:3b` did not mention `createdAt`/date-time in the legacy-item case. In both models most facts cite no concrete
value (they paraphrase), so grounding could be checked for 32 % (qwen) and 37 % (llama) of facts; every checkable fact
was found in the data.

### Test suggestions (7 operations)

| | `qwen2.5:7b` | `llama3.2:3b` |
|---|---|---|
| Valid structured output | **7 / 7** (no retry needed) | **7 / 7** |
| Suggestions stored for review | 28 | 13 |
| Discarded: duplicate of an existing test or another suggestion | 2 | 12 |
| Discarded: not a valid test | 5 (all five PATCH suggestions omitted the required `productId`) | 12 |
| Discarded: more than the 5 asked for | 0 | 10 (it answered with 10 twice) |
| Stored, flagged as a possible duplicate (one value differs) | 8 | 1 |
| Stored, using query or body fields the complete generated request does not use | 3 | 0 |
| Stored, expecting a status the operation does not document | 2 | 0 |
| Latency p50 / p95 / max | 33.0 s / 63.7 s / 63.7 s | 8.1 s / 14.7 s / 14.7 s |

**Manual review of the 28 `qwen2.5:7b` suggestions** (one reviewer, the developer, judging each by its name and intent
against the contract; not an automated score):

| Judgement | Count | Examples |
|---|---|---|
| Useful: a check the deterministic tests do not make, consistent with the contract | 7 | invalid `sku` pattern; duplicate `tags` (uniqueItems); `tags: null`; `productId` with leading zeros; malformed `delivery` address and pickup point (the generator skips oneOf); invalid `couponCode` pattern |
| Marginal: valid, but overlaps existing tests or adds little | 10 | `limit` boundaries combined with a `category`; `heightCm` at the exclusive minimum (already generated); empty `tags`; order without `couponCode` (an optional field) |
| Not useful or wrong | 11 | four `GET /health` "tests" about response fields a client cannot influence; "productId at maximum / above maximum" and "enum" variants for `productId`, which has no maximum or enum (invented constraints); a "maximum length" of `status` the schema does not define |

So roughly a quarter of the stored suggestions add value, and review is required — which is why every suggestion
waits for a person and none runs before it is accepted. The `llama3.2:3b` suggestions were not classified in the same
detail; among them "missing required query parameter `category`" rests on a wrong premise (`category` is optional).

## Defects the evaluation found (fixed before these runs were final)

1. **Server error on a `null` value** — `llama3.2:3b` answered with `"value": null` in a request body; the shared
   schema validator's pattern `switch` threw on `null` against a schema without `type`, turning two suggestion
   requests into 500 errors. Fixed (`case null`); the same bug could have crashed contract validation of a `null`
   response field. Regression tests in `SchemaValidatorTest` and `AiRulesTest`.
2. **Code-claim false positives** — both flagged "code claims" are the JSON parser's position ("not valid JSON at line
   1, column 17") that the models quoted from the data. The detector now ignores phrases present in the model's input
   (unit test added). The runs above were scored with the old detector, which is why each shows 1. Applying the fixed
   rule to all stored answers (with the JSON_SYNTAX assertion message the models received, "The body is not valid JSON
   at line 1, column 17.") gives 0 claims for both models.
3. **Exact duplicates slipped through** in a first, unrecorded smoke run because path and query values were compared
   with their JSON types (`1` vs `"1"`); they travel as text, and are now compared as text. Near-duplicates (one value
   differs) are now flagged for the reviewer, and the prompt now forbids inventing constraints.

## Conclusions

- Both models produced valid structured output for every request with the schema-constrained `format`; the bounded
  retry was not needed in these runs (it was needed once in a smoke run before the final prompt).
- For **explanations** both models are useful and equally relevant on this dataset; `llama3.2:3b` is about 3 × faster
  and cites concrete values more often.
- For **suggestions** `qwen2.5:7b` is clearly better: more valid, fewer duplicate and invalid answers, though only about
  a quarter are useful by manual review and some invent constraints. `llama3.2:3b` mostly repeats existing tests.
- Default: `qwen2.5:7b` for machines like this one (GPU with ≥ 6 GB or 16 GB RAM); `llama3.2:3b` where memory is tight
  or speed matters more than suggestion quality. The model stays configurable (`APIFORGE_AI_MODEL`).
- These results come from one small dataset on one machine. They show the pipeline works and how two models behave
  here; they are not a general accuracy claim.
