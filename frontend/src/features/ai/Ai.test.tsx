import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockEnvironmentsApi } from '@/features/environments/test-fixtures'
import type { ResultDetail, Run } from '@/features/runs/api'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import type { TestDetail, TestSummary } from '@/features/tests/api'
import { renderApp } from '@/test/render'
import { AI_READY, problem, server } from '@/test/server'

import type { Explanation, SuggestionReport } from './api'

const BASE = `/api/v1/projects/${PROJECT.id}`

function summary(id: string, overrides: Partial<TestSummary> = {}): TestSummary {
  return {
    id,
    suiteId: 'suite-ai',
    operationKey: 'POST /orders',
    method: 'POST',
    path: '/orders',
    name: `Test ${id}`,
    category: 'CUSTOM',
    origin: 'AI_SUGGESTED',
    reviewStatus: 'PENDING_REVIEW',
    lifecycleStatus: 'ACTIVE',
    riskLevel: 'MUTATING',
    enabled: true,
    userModified: false,
    expectedStatuses: ['400'],
    sortOrder: 10000,
    updatedAt: '2026-10-22T10:00:00Z',
    version: 0,
    ...overrides,
  }
}

function detail(test: TestSummary): TestDetail {
  return {
    ...test,
    executable: test.reviewStatus === 'APPROVED',
    scenarioKey: null,
    specLocation: null,
    rationale: 'Suggested by qwen2.5:7b. A negative quantity must be rejected.',
    generatorVersion: 'ai-suggest-1',
    aiModel: 'qwen2.5:7b',
    sourceSpecificationVersionId: 'v-1',
    request: { pathParams: {}, query: {}, headers: {}, body: { mode: 'JSON', contentType: 'application/json', value: { lines: [{ quantity: -1 }] } }, auth: 'ENVIRONMENT' },
    expectations: { statuses: test.expectedStatuses, summary: 'Rejected.', validateResponseSchema: true, responseChecks: [] },
    reviewedBy: null,
    reviewedAt: null,
    createdAt: '2026-10-22T10:00:00Z',
  }
}

function mockTests() {
  const state = { tests: [summary('g-1', { origin: 'DETERMINISTIC', reviewStatus: 'APPROVED', name: 'Valid request' })], reviews: [] as unknown[], suggest: 0 }
  server.use(
    http.get(`${BASE}/tests/summary`, () =>
      HttpResponse.json({ total: state.tests.length, enabled: state.tests.length, stale: 0, operations: [{ operationKey: 'POST /orders', total: 1, enabled: 1, stale: 0 }] }),
    ),
    http.get(`${BASE}/tests`, ({ request }) => {
      const params = new URL(request.url).searchParams
      const content = state.tests.filter(
        (test) => (!params.get('origin') || test.origin === params.get('origin')) && (!params.get('reviewStatus') || test.reviewStatus === params.get('reviewStatus')),
      )
      return HttpResponse.json({ content, page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 } })
    }),
    http.get(`${BASE}/tests/generation-plan`, () =>
      HttpResponse.json({ operationKey: 'POST /orders', specificationVersionId: 'v-1', generatorVersion: '2', scenarios: [], skipped: [] }),
    ),
    http.get(`${BASE}/tests/:testId`, ({ params }) => HttpResponse.json(detail(state.tests.find((test) => test.id === params.testId)!))),
    http.get(`${BASE}/test-suites`, () => HttpResponse.json([])),
    http.post(`${BASE}/tests/:testId/review`, async ({ params, request }) => {
      const body = (await request.json()) as { decision: string }
      state.reviews.push({ id: params.testId, ...body })
      state.tests = state.tests.map((test) =>
        test.id === params.testId ? { ...test, reviewStatus: body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED' } : test,
      )
      return HttpResponse.json(detail(state.tests.find((test) => test.id === params.testId)!))
    }),
  )
  return state
}

const REPORT: SuggestionReport = {
  operationKey: 'POST /orders',
  model: 'qwen2.5:7b',
  latencyMs: 41_230,
  attempts: 2,
  created: [detail(summary('ai-1', { name: 'Negative quantity' }))],
  discarded: [{ name: 'Credentials in a header', reason: 'Not a valid test: The test definition is invalid.' }],
  possibleDuplicates: [{ name: 'Negative quantity', similarTo: 'Quantity below minimum 1', difference: 'body/lines/0/quantity' }],
}

describe('AI status', () => {
  it('shows the configured model and lets you check again', async () => {
    mockSpecApi({ versions: 1 })
    let refreshed = 0
    server.use(
      http.get('/api/v1/ai/status', ({ request }) => {
        if (new URL(request.url).searchParams.get('refresh') === 'true') {
          refreshed += 1
          return HttpResponse.json({ ...AI_READY, state: 'UNAVAILABLE', installedModels: [], message: 'Ollama is not reachable at http://localhost:11434.' })
        }
        return HttpResponse.json(AI_READY)
      }),
    )
    const user = userEvent.setup()
    renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'AI: Ready' }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByText(/Ollama is reachable and the model is installed/)).toBeInTheDocument()
    expect(within(menu).getByText(/never depend on it/)).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: 'Check again' }))
    await waitFor(() => expect(refreshed).toBe(1))
    expect(await within(menu).findByText(/Ollama is not reachable/)).toBeInTheDocument()
  })
})

describe('AI suggestions', () => {
  it('asks for suggestions, reports what was kept and discarded, and lets you accept, edit or reject', async () => {
    mockSpecApi({ versions: 1 })
    const state = mockTests()
    server.use(
      http.post(`${BASE}/ai/suggestions`, async ({ request }) => {
        state.suggest += 1
        expect(await request.json()).toEqual({ operationKey: 'POST /orders' })
        await delay(50)
        state.tests = [...state.tests, summary('ai-1', { name: 'Negative quantity' })]
        return HttpResponse.json(REPORT)
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/tests?operation=${encodeURIComponent('POST /orders')}`)

    const panel = await screen.findByRole('region', { name: 'AI suggestions' })
    expect(within(panel).getByText(/nothing runs until you accept it/)).toBeInTheDocument()
    await user.click(within(panel).getByRole('button', { name: 'Suggest tests' }))
    expect(await within(panel).findByText(/Asking qwen2.5:7b for suggestions/)).toBeInTheDocument()
    expect(await within(panel).findByText(/1 suggestion added for review/)).toBeInTheDocument()
    expect(within(panel).getByText(/41.2 s, 2 attempts/)).toBeInTheDocument()
    expect(within(panel).getByText(/may duplicate “Quantity below minimum 1” \(differs only in body\/lines\/0\/quantity\)/)).toBeInTheDocument()
    expect(within(panel).getByText('1 suggestion was discarded')).toBeInTheDocument()

    const waiting = await within(panel).findByRole('list', { name: 'Suggestions waiting for review' })
    expect(within(waiting).getByText('Negative quantity')).toBeInTheDocument()
    await user.click(within(waiting).getByRole('button', { name: 'Edit' }))
    expect(await screen.findByRole('dialog')).toHaveTextContent('Negative quantity')
    await user.keyboard('{Escape}')
    await user.click(within(waiting).getByRole('button', { name: 'Accept' }))
    await waitFor(() => expect(state.reviews).toEqual([{ id: 'ai-1', decision: 'APPROVE' }]))
    await waitFor(() => expect(within(panel).queryByRole('list', { name: 'Suggestions waiting for review' })).not.toBeInTheDocument())
  })

  it('shows errors with a retry and is disabled while AI is unavailable', async () => {
    mockSpecApi({ versions: 1 })
    mockTests()
    server.use(http.post(`${BASE}/ai/suggestions`, () => problem(502, 'AI_INVALID_OUTPUT', 'The answer is not valid JSON.')))
    const user = userEvent.setup()
    const { queryClient } = renderApp(`/projects/${PROJECT.id}/tests?operation=${encodeURIComponent('POST /orders')}`)

    const panel = await screen.findByRole('region', { name: 'AI suggestions' })
    await user.click(within(panel).getByRole('button', { name: 'Suggest tests' }))
    expect(await within(panel).findByText('The answer is not valid JSON.')).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Try again' })).toBeEnabled()

    server.use(http.get('/api/v1/ai/status', () => HttpResponse.json({ ...AI_READY, state: 'MODEL_MISSING', message: 'The model qwen2.5:7b is not installed. Run: ollama pull qwen2.5:7b' })))
    await queryClient.invalidateQueries({ queryKey: ['ai', 'status'] })
    expect(await within(panel).findByText(/ollama pull qwen2.5:7b/)).toBeInTheDocument()
    expect(within(panel).getByRole('button', { name: 'Suggest tests' })).toBeDisabled()
  })
})

const RUN: Run = {
  id: 'run-1',
  projectId: PROJECT.id,
  kind: 'TEST_RUN',
  status: 'COMPLETED',
  statusReason: null,
  environmentId: 'env-1',
  environmentName: 'Lab',
  baseUrl: 'http://127.0.0.1:8090',
  suiteId: null,
  specificationVersionId: 'v-1',
  triggeredBy: 'user-1',
  counts: { total: 1, passed: 0, failed: 1, inconclusive: 0, error: 0, skipped: 0 },
  createdAt: '2026-10-22T10:00:00Z',
  startedAt: '2026-10-22T10:00:01Z',
  finishedAt: '2026-10-22T10:00:02Z',
}

const FAILED: ResultDetail = {
  id: 'res-f',
  testRunId: 'run-1',
  testCaseId: 't-1',
  testName: 'Valid request',
  operationKey: 'GET /health',
  outcome: 'FAILED',
  request: { method: 'GET', url: 'http://127.0.0.1:8090/health', headers: {}, body: { mode: 'NONE' } },
  response: { status: 200, headers: {}, body: '{"status":"DEGRADED"}', sizeBytes: 21, truncated: false, contentType: 'application/json' },
  checks: [{ name: 'BODY_SCHEMA', result: 'FAIL', message: '$.status must be one of UP.', path: '$.status', keyword: 'enum', expected: 'one of UP', actual: 'string "DEGRADED"' }],
  errorKind: null,
  errorMessage: null,
  durationMs: 5,
  timeToHeadersMs: 4,
  correlationId: 'c-1',
  testCase: null,
  executedAt: '2026-10-22T10:00:02Z',
}

const EXPLANATION: Explanation = {
  id: 'ex-1',
  resultId: 'res-f',
  model: 'qwen2.5:7b',
  latencyMs: 18_400,
  createdAt: '2026-10-22T10:01:00Z',
  summary: 'The health endpoint reports DEGRADED, which the contract does not allow.',
  observedFacts: [
    { statement: 'The body field $.status is "DEGRADED".', source: 'RESPONSE', grounding: 'VERIFIED' },
    { statement: 'The status was 503.', source: 'RESPONSE', grounding: 'UNVERIFIED' },
  ],
  likelyCauses: [{ hypothesis: 'A dependency of the service is unhealthy.', confidence: 'MEDIUM', evidence: [{ text: '$.status', grounding: 'VERIFIED' }] }],
  debuggingSteps: ['Check which dependency reports degraded health.'],
  suggestedChanges: [{ target: 'SPECIFICATION', change: 'Document DEGRADED if it is a valid state.' }],
  inputs: ['failed and inconclusive assertions with expected and actual values'],
  sourceCodeProvided: false,
  warnings: ['1 observed fact cites a value not found in the data the model was given.'],
}

function mockRun() {
  mockEnvironmentsApi([])
  server.use(
    http.get(`${BASE}/runs/run-1`, () => HttpResponse.json(RUN)),
    http.get(`${BASE}/runs/run-1/results`, () =>
      HttpResponse.json({
        content: [{ ...FAILED, responseStatus: 200, failedChecks: 1, skippedChecks: 0, firstFailure: '$.status must be one of UP.' }],
        page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
      }),
    ),
    http.get(`${BASE}/runs/run-1/results/res-f`, () => HttpResponse.json(FAILED)),
  )
}

describe('Explain failure', () => {
  it('explains a failed result, keeping observed facts apart from hypotheses', async () => {
    mockSpecApi({ versions: 1 })
    mockRun()
    let posts = 0
    server.use(
      http.get(`${BASE}/runs/run-1/results/res-f/explanation`, () => problem(404, 'NOT_FOUND', 'Explanation not found.')),
      http.post(`${BASE}/runs/run-1/results/res-f/explanation`, async () => {
        posts += 1
        await delay(50)
        return HttpResponse.json(EXPLANATION)
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/runs/run-1?result=res-f`)

    const section = await screen.findByRole('region', { name: 'AI explanation' })
    await user.click(await within(section).findByRole('button', { name: 'Explain with AI' }))
    expect(await within(section).findByText(/is reading the result/)).toBeInTheDocument()
    expect(await within(section).findByText(EXPLANATION.summary)).toBeInTheDocument()
    expect(posts).toBe(1)
    const facts = within(section).getByText('Observed facts').parentElement!
    expect(within(facts).getByText('found in the data')).toBeInTheDocument()
    expect(within(facts).getByText('not found in the data')).toBeInTheDocument()
    expect(within(section).getByText('Likely causes (hypotheses)')).toBeInTheDocument()
    expect(within(section).getByText('medium confidence')).toBeInTheDocument()
    expect(within(section).getByText(/APIForge applies nothing automatically/)).toBeInTheDocument()
    expect(within(section).getByText(/1 observed fact cites a value not found/)).toBeInTheDocument()
    expect(within(section).getByText(/did not see the API's source code/)).toBeInTheDocument()
    expect(within(section).getByText(/qwen2.5:7b · 18.4 s/)).toBeInTheDocument()
    expect(within(section).getByRole('button', { name: 'Explain again' })).toBeEnabled()
  })

  it('shows a stored explanation and reports model errors', async () => {
    mockSpecApi({ versions: 1 })
    mockRun()
    server.use(
      http.get(`${BASE}/runs/run-1/results/res-f/explanation`, () => HttpResponse.json(EXPLANATION)),
      http.post(`${BASE}/runs/run-1/results/res-f/explanation`, () => problem(504, 'AI_TIMEOUT', 'The model did not answer within the request timeout.')),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/runs/run-1?result=res-f`)

    const section = await screen.findByRole('region', { name: 'AI explanation' })
    expect(await within(section).findByText(EXPLANATION.summary)).toBeInTheDocument()
    await user.click(within(section).getByRole('button', { name: 'Explain again' }))
    expect(await within(section).findByText('The model did not answer within the request timeout.')).toBeInTheDocument()
    expect(within(section).getByText(EXPLANATION.summary)).toBeInTheDocument()
  })
})
