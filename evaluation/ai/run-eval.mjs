// Evaluates APIForge's AI features against a running instance and a local model.
//
//   node evaluation/ai/run-eval.mjs [--base http://127.0.0.1:8080] [--out evaluation/ai/results] [--label name]
//
// Needs: the APIForge backend (with Ollama configured), and samples/warehouse-lab-server.mjs on
// 127.0.0.1:8090 started with WAREHOUSE_LAB_VIOLATIONS=1. Creates a throwaway user and project.
// Scores are computed here from what the API returned; nothing is estimated.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const root = join(here, '..', '..')
const args = Object.fromEntries(process.argv.slice(2).reduce((pairs, value, index, all) =>
  value.startsWith('--') ? [...pairs, [value.slice(2), all[index + 1]]] : pairs, []))
const base = args.base ?? 'http://127.0.0.1:8080'
const outDir = args.out ?? join(here, 'results')
const dataset = JSON.parse(readFileSync(join(here, 'dataset.json'), 'utf8'))

// ---- a tiny API client with a cookie jar and CSRF ----------------------------------------------
const cookies = new Map()
async function call(method, path, body, isFile) {
  const headers = { Cookie: [...cookies].map(([k, v]) => `${k}=${v}`).join('; ') }
  if (cookies.has('XSRF-TOKEN')) headers['X-XSRF-TOKEN'] = decodeURIComponent(cookies.get('XSRF-TOKEN'))
  let payload
  if (isFile) {
    const form = new FormData()
    form.append('file', new Blob([body.content], { type: 'application/yaml' }), body.name)
    payload = form
  } else if (body !== undefined) {
    headers['Content-Type'] = 'application/json'
    payload = JSON.stringify(body)
  }
  const started = performance.now()
  const response = await fetch(base + path, { method, headers, body: payload })
  const elapsed = Math.round(performance.now() - started)
  for (const line of response.headers.getSetCookie()) {
    const [pair] = line.split(';')
    const [name, ...value] = pair.split('=')
    cookies.set(name.trim(), value.join('='))
  }
  const text = await response.text()
  let json = null
  try { json = text ? JSON.parse(text) : null } catch { json = null }
  return { status: response.status, json, elapsed }
}

/** AI calls respect the per-user rate limit: on 429 wait as told and send again (latency is of the answered call). */
async function aiCall(method, path, body) {
  for (;;) {
    const response = await call(method, path, body)
    if (response.status !== 429) return response
    const wait = Number(response.json?.retryAfterSeconds ?? 10)
    console.log(`  rate limited, waiting ${wait} s`)
    await new Promise((resolve) => setTimeout(resolve, (wait + 1) * 1000))
  }
}

const stamp = Date.now()
await call('GET', '/api/v1/auth/csrf')
const registered = await call('POST', '/api/v1/auth/register', {
  email: `ai-eval-${stamp}@example.test`, displayName: 'AI Evaluation', password: 'quartz-meadow-signal-58',
})
if (registered.status !== 201 && registered.status !== 200) throw new Error(`register failed: ${registered.status}`)
await call('GET', '/api/v1/auth/csrf')

const status = (await call('GET', '/api/v1/ai/status?refresh=true')).json
console.log(`AI status: ${status.state} model=${status.model} server=${status.server}`)
if (status.state !== 'READY') throw new Error(status.message)

const project = (await call('POST', '/api/v1/projects', { name: `AI evaluation ${stamp}` })).json
const P = `/api/v1/projects/${project.id}`
const spec = readFileSync(join(root, dataset.spec), 'utf8')
if ((await call('POST', `${P}/specs`, { content: spec, name: 'warehouse.yaml' }, true)).status !== 201) throw new Error('import failed')
await call('POST', `${P}/tests/generate`, {})

const environment = async (name, timeoutMs) => {
  const created = (await call('POST', `${P}/environments`, {
    name, baseUrl: 'http://127.0.0.1:8090', timeoutMs, followRedirects: false, executionPolicy: 'ALLOW_DESTRUCTIVE',
    authorizationConfirmed: true, auth: { type: 'API_KEY_HEADER', headerName: 'X-API-Key', valueVariable: 'API_KEY' },
    defaultHeaders: {},
  })).json
  await call('PUT', `${P}/environments/${created.id}/variables/API_KEY`, { value: 'lab-demo-key', secret: true })
  return created.id
}
const environments = { default: await environment('Lab', 5000), slow: await environment('Lab (1 s timeout)', 1000) }

const started = (await call('POST', `${P}/runs`, { environmentId: environments.default })).json
let run
do {
  await new Promise((resolve) => setTimeout(resolve, 500))
  run = (await call('GET', `${P}/runs/${started.id}`)).json
} while (!['COMPLETED', 'CANCELLED', 'INTERRUPTED'].includes(run.status))
const results = []
for (let page = 0; ; page++) {
  const listed = (await call('GET', `${P}/runs/${run.id}/results?size=100&page=${page}`)).json
  results.push(...listed.content)
  if (page + 1 >= listed.page.totalPages) break
}
console.log(`run: ${JSON.stringify(run.counts)}`)

// ---- explanations ------------------------------------------------------------------------------
const textOf = (e) => [e.summary, ...e.observedFacts.map((f) => f.statement), ...e.likelyCauses.flatMap((c) => [c.hypothesis, ...c.evidence.map((x) => x.text)]),
  ...e.debuggingSteps, ...e.suggestedChanges.map((c) => c.change)].join('\n').toLowerCase()
const explanationRows = []
for (const item of dataset.explanations) {
  let runId
  let resultId
  if (item.find) {
    const found = results.find((r) => r.operationKey === item.find.operationKey && r.testName === item.find.testName)
    if (!found) throw new Error(`no result for ${item.id}`)
    runId = run.id
    resultId = found.id
  } else {
    const executed = (await call('POST', `${P}/executions`, {
      environmentId: environments[item.console.environment], method: item.console.method, path: item.console.path,
      request: { pathParams: {}, query: item.console.query, headers: {}, body: { mode: 'NONE' }, auth: 'ENVIRONMENT' },
    })).json
    runId = executed.testRunId
    resultId = executed.id
  }
  const response = await aiCall('POST', `${P}/runs/${runId}/results/${resultId}/explanation`, {})
  const row = { id: item.id, httpStatus: response.status, latencyMs: response.elapsed }
  if (response.status === 200) {
    const e = response.json
    const text = textOf(e)
    const groups = item.mustMention.map((group) => group.some((term) => text.includes(term.toLowerCase())))
    const checkable = e.observedFacts.filter((f) => f.grounding !== 'NOT_CHECKABLE')
    Object.assign(row, {
      model: e.model,
      relevance: groups.filter(Boolean).length / groups.length,
      missing: item.mustMention.filter((_, index) => !groups[index]).map((group) => group.join('|')),
      facts: e.observedFacts.length,
      factsVerified: checkable.filter((f) => f.grounding === 'VERIFIED').length,
      factsCheckable: checkable.length,
      causes: e.likelyCauses.length,
      codeClaim: e.warnings.some((w) => w.includes('source code')),
      explanation: e,
    })
  } else {
    row.error = response.json?.code ?? response.status
    row.detail = response.json?.detail
  }
  explanationRows.push(row)
  console.log(`explain ${item.id}: ${row.httpStatus} ${row.latencyMs} ms ${row.relevance !== undefined ? `relevance ${row.relevance.toFixed(2)} facts ${row.factsVerified}/${row.factsCheckable} verified` : row.error}`)
}

// ---- suggestions -------------------------------------------------------------------------------
const testsOf = async (operationKey) => {
  const listed = (await call('GET', `${P}/tests?size=100&operationKey=${encodeURIComponent(operationKey)}`)).json.content
  const details = []
  for (const test of listed) details.push((await call('GET', `${P}/tests/${test.id}`)).json)
  return details
}
const suggestionRows = []
for (const item of dataset.suggestions) {
  const before = await testsOf(item.operationKey)
  const complete = before.find((t) => t.scenarioKey === 'happy.complete') ?? before.find((t) => t.scenarioKey === 'happy.minimal')
  const knownQuery = new Set(Object.keys(complete?.request.query ?? {}))
  const knownBody = new Set(Object.keys(complete?.request.body?.value ?? {}))
  const documented = (await call('GET', `${P}/coverage/operation?key=${encodeURIComponent(item.operationKey)}`)).json.responses.map((r) => r.status)
  const response = await aiCall('POST', `${P}/ai/suggestions`, { operationKey: item.operationKey })
  const row = { operationKey: item.operationKey, httpStatus: response.status, latencyMs: response.elapsed }
  if (response.status === 200) {
    const report = response.json
    const created = report.created
    const undocumentedInputs = created.filter((t) => Object.keys(t.request.query).some((k) => !knownQuery.has(k))
      || (t.request.body?.value && typeof t.request.body.value === 'object' && !Array.isArray(t.request.body.value)
        && Object.keys(t.request.body.value).some((k) => !knownBody.has(k)))).length
    const undocumentedStatuses = created.filter((t) => t.expectations.statuses.some((s) => !documented.includes(s)
      && !/^[1-5]XX$/.test(s))).length
    Object.assign(row, {
      model: report.model,
      attempts: report.attempts,
      created: created.length,
      discardedDuplicate: report.discarded.filter((d) => d.reason.includes('same request') || d.reason.startsWith('Same as the suggestion')).length,
      discardedInvalid: report.discarded.filter((d) => d.reason.startsWith('Not a valid test')).length,
      discardedOther: report.discarded.filter((d) => !d.reason.includes('same request') && !d.reason.startsWith('Same as the suggestion') && !d.reason.startsWith('Not a valid test')).length,
      overlapsWithReason: created.filter((t) => t.rationale.includes('Overlaps with')).length,
      possibleDuplicates: report.possibleDuplicates.length,
      undocumentedInputs,
      undocumentedStatuses,
      names: created.map((t) => t.name),
      discarded: report.discarded,
    })
  } else {
    row.error = response.json?.code ?? response.status
    row.detail = response.json?.detail
  }
  suggestionRows.push(row)
  console.log(`suggest ${item.operationKey}: ${row.httpStatus} ${row.latencyMs} ms ${row.created !== undefined ? `created ${row.created}, discarded ${row.discardedDuplicate + row.discardedInvalid + row.discardedOther}` : row.error}`)
}

// ---- summary -----------------------------------------------------------------------------------
const ok = explanationRows.filter((r) => r.httpStatus === 200)
const okS = suggestionRows.filter((r) => r.httpStatus === 200)
const pct = (n, d) => (d === 0 ? '–' : `${Math.round((n / d) * 100)}%`)
const latencies = (rows) => rows.map((r) => r.latencyMs).sort((a, b) => a - b)
const quantile = (values, q) => (values.length === 0 ? null : values[Math.min(values.length - 1, Math.ceil(q * values.length) - 1)])
const summary = {
  label: args.label ?? status.model,
  model: status.model,
  when: new Date().toISOString(),
  run: run.counts,
  explanations: {
    cases: explanationRows.length,
    validStructuredOutput: ok.length,
    meanRelevance: ok.length ? +(ok.reduce((s, r) => s + r.relevance, 0) / ok.length).toFixed(2) : null,
    fullyRelevant: ok.filter((r) => r.relevance === 1).length,
    factsVerified: ok.reduce((s, r) => s + r.factsVerified, 0),
    factsCheckable: ok.reduce((s, r) => s + r.factsCheckable, 0),
    factsTotal: ok.reduce((s, r) => s + r.facts, 0),
    codeClaims: ok.filter((r) => r.codeClaim).length,
    latencyMs: { p50: quantile(latencies(explanationRows), 0.5), p95: quantile(latencies(explanationRows), 0.95), max: Math.max(...latencies(explanationRows)) },
  },
  suggestions: {
    operations: suggestionRows.length,
    validStructuredOutput: okS.length,
    created: okS.reduce((s, r) => s + r.created, 0),
    discardedDuplicate: okS.reduce((s, r) => s + r.discardedDuplicate, 0),
    discardedInvalid: okS.reduce((s, r) => s + r.discardedInvalid, 0),
    discardedOther: okS.reduce((s, r) => s + r.discardedOther, 0),
    overlapsWithReason: okS.reduce((s, r) => s + r.overlapsWithReason, 0),
    possibleDuplicates: okS.reduce((s, r) => s + r.possibleDuplicates, 0),
    undocumentedInputs: okS.reduce((s, r) => s + r.undocumentedInputs, 0),
    undocumentedStatuses: okS.reduce((s, r) => s + r.undocumentedStatuses, 0),
    retried: okS.filter((r) => r.attempts > 1).length,
    latencyMs: { p50: quantile(latencies(suggestionRows), 0.5), p95: quantile(latencies(suggestionRows), 0.95), max: Math.max(...latencies(suggestionRows)) },
  },
}
mkdirSync(outDir, { recursive: true })
const file = join(outDir, `${summary.label.replace(/[^A-Za-z0-9.-]+/g, '_')}.json`)
writeFileSync(file, JSON.stringify({ summary, explanations: explanationRows, suggestions: suggestionRows }, null, 2))
const e = summary.explanations
const s = summary.suggestions
console.log(`\n${summary.label}`)
console.log(`explanations: ${e.validStructuredOutput}/${e.cases} valid; relevance mean ${e.meanRelevance}, fully relevant ${e.fullyRelevant}/${e.validStructuredOutput}; facts verified ${e.factsVerified}/${e.factsCheckable} checkable (${pct(e.factsVerified, e.factsCheckable)}), ${e.factsTotal} total; code claims ${e.codeClaims}; latency p50 ${e.latencyMs.p50} ms p95 ${e.latencyMs.p95} ms max ${e.latencyMs.max} ms`)
console.log(`suggestions: ${s.validStructuredOutput}/${s.operations} valid; created ${s.created}; discarded duplicate ${s.discardedDuplicate}, invalid ${s.discardedInvalid}, other ${s.discardedOther}; overlaps with reason ${s.overlapsWithReason}; flagged possible duplicates ${s.possibleDuplicates}; undocumented inputs ${s.undocumentedInputs}, undocumented statuses ${s.undocumentedStatuses}; retried ${s.retried}; latency p50 ${s.latencyMs.p50} ms p95 ${s.latencyMs.p95} ms max ${s.latencyMs.max} ms`)
console.log(`written to ${file}`)
