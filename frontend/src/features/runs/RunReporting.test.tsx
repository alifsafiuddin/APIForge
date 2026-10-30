import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

import type { ResultDetail, ResultSummary, Run, RunComparison } from './api'
import { childPath, parsePath, toPath } from './jsonPath'

const BASE = `/api/v1/projects/${PROJECT.id}`

function run(overrides: Partial<Run> = {}): Run {
  return {
    id: 'run-2',
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
    counts: { total: 3, passed: 1, failed: 1, inconclusive: 0, error: 0, skipped: 1 },
    createdAt: '2026-10-21T10:00:00Z',
    startedAt: '2026-10-21T10:00:01Z',
    finishedAt: '2026-10-21T10:00:05Z',
    ...overrides,
  }
}

const page = <T,>(content: T[]) => ({ content, page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 } })

const FAILED: ResultSummary = {
  id: 'res-f',
  testCaseId: 't-1',
  testName: 'List products',
  operationKey: 'GET /products',
  outcome: 'FAILED',
  responseStatus: 200,
  durationMs: 40,
  errorKind: null,
  errorMessage: null,
  failedChecks: 2,
  skippedChecks: 0,
  firstFailure: '$.items[0].price expected number but found string.',
  executedAt: '2026-10-21T10:00:02Z',
}

const PASSED: ResultSummary = {
  ...FAILED,
  id: 'res-p',
  testName: 'Delete a product',
  operationKey: 'DELETE /products/{productId}',
  outcome: 'PASSED',
  responseStatus: 204,
  durationMs: 10,
  failedChecks: 0,
  skippedChecks: 1,
  firstFailure: null,
}

const FAILED_DETAIL: ResultDetail = {
  id: 'res-f',
  testRunId: 'run-2',
  testCaseId: 't-1',
  testName: 'List products',
  operationKey: 'GET /products',
  outcome: 'FAILED',
  request: { method: 'GET', url: 'http://127.0.0.1:8090/products', headers: {}, body: { mode: 'NONE' } },
  response: {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
    body: '{"items":[{"id":7,"price":"19.90"}],"total":1}',
    sizeBytes: 47,
    truncated: false,
    contentType: 'application/json',
  },
  checks: [
    { name: 'STATUS', result: 'PASS', message: 'Status 200 is what the test expects (200).', expected: '200', actual: '200' },
    { name: 'STATUS_DOCUMENTED', result: 'PASS', message: 'Status 200 is documented.' },
    { name: 'CONTENT_TYPE', result: 'PASS', message: "Content-Type 'application/json' matches the documented 'application/json'." },
    { name: 'JSON_SYNTAX', result: 'PASS', message: 'The body is well-formed JSON.' },
    {
      name: 'BODY_SCHEMA',
      result: 'FAIL',
      message: '$.items[0].price expected number but found string.',
      path: '$.items[0].price',
      keyword: 'type',
      expected: 'number',
      actual: 'string "19.90"',
    },
    {
      name: 'BODY_SCHEMA',
      result: 'FAIL',
      message: "$.items[0].sku required property 'sku' is missing.",
      path: '$.items[0].sku',
      keyword: 'required',
      expected: "property 'sku'",
      actual: 'missing',
    },
  ],
  errorKind: null,
  errorMessage: null,
  durationMs: 40,
  timeToHeadersMs: 30,
  correlationId: 'corr-2',
  testCase: null,
  executedAt: '2026-10-21T10:00:02Z',
}

describe('Run reporting', () => {
  it('filters results by outcome and shows assertions, expected versus actual, the marked body and timing', async () => {
    mockSpecApi({ versions: 1 })
    const requested: string[] = []
    server.use(
      http.get(`${BASE}/runs/run-2`, () => HttpResponse.json(run())),
      http.get(`${BASE}/runs/run-2/results`, ({ request }) => {
        const outcome = new URL(request.url).searchParams.get('outcome')
        requested.push(outcome ?? 'ALL')
        return HttpResponse.json(page(outcome === 'FAILED' ? [FAILED] : [FAILED, PASSED]))
      }),
      http.get(`${BASE}/runs/run-2/results/res-f`, () => HttpResponse.json(FAILED_DETAIL)),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/runs/run-2`)

    const results = await screen.findByRole('list', { name: 'Results' })
    expect(within(results).getAllByRole('listitem')).toHaveLength(2)
    expect(within(results).getByText(/expected number but found string\. \(\+1 more\)/)).toBeInTheDocument()
    expect(within(results).getByText(/1 check skipped/)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Failed/ }))
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Results' })).getAllByRole('listitem')).toHaveLength(1))
    expect(requested).toContain('FAILED')
    expect(screen.getByRole('button', { name: /^Failed/ })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: /^Error/ })).toBeDisabled()

    await user.click(screen.getByRole('button', { name: /List products/ }))
    const sheet = await screen.findByRole('dialog')
    expect(await within(sheet).findByText('6 checks: 4 passed, 2 failed')).toBeInTheDocument()
    const priceRow = within(sheet).getByRole('row', { name: /\$\.items\[0\]\.price/ })
    expect(within(priceRow).getByText('FAIL')).toBeInTheDocument()
    expect(within(priceRow).getByText('number')).toBeInTheDocument()
    expect(within(priceRow).getByText('string "19.90"')).toBeInTheDocument()

    const tree = within(sheet).getByRole('list', { name: 'Response body' })
    const price = tree.querySelector('[data-json-path="$.items[0].price"]') as HTMLElement
    expect(price).toHaveTextContent('price: "19.90"')
    expect(price).toHaveTextContent('expected number but found string')
    const sku = tree.querySelector('[data-json-path="$.items[0].sku"]') as HTMLElement
    expect(sku).toHaveTextContent('sku: missing')
    expect(within(sheet).getByText('2 body findings marked.')).toBeInTheDocument()

    await user.click(within(priceRow).getByRole('button', { name: '$.items[0].price' }))
    expect(price).toHaveFocus()

    const timing = within(sheet).getByRole('region', { name: 'Timing' })
    expect(within(timing).getByText('30 ms')).toBeInTheDocument()
    expect(within(timing).getByText('10 ms')).toBeInTheDocument()
    expect(within(timing).getByText('40 ms')).toBeInTheDocument()

    await user.click(within(sheet).getByRole('button', { name: 'Show raw' }))
    expect(within(sheet).getByText(/"price": "19.90"/)).toBeInTheDocument()
  })

  it('compares a run with the previous one and hides unchanged tests by default', async () => {
    mockSpecApi({ versions: 1 })
    const comparison: RunComparison = {
      baseline: run({ id: 'run-1', counts: { total: 3, passed: 2, failed: 0, inconclusive: 0, error: 0, skipped: 1 } }),
      current: run(),
      counts: { REGRESSED: 1, FIXED: 0, CHANGED: 0, NEW: 0, REMOVED: 0, UNCHANGED: 2 },
      changes: [
        { testName: 'List products', operationKey: 'GET /products', testCaseId: 't-1', baselineOutcome: 'PASSED', currentOutcome: 'FAILED', kind: 'REGRESSED', baselineResultId: 'old-f', currentResultId: 'res-f' },
        { testName: 'Delete a product', operationKey: 'DELETE /products/{productId}', testCaseId: 't-2', baselineOutcome: 'PASSED', currentOutcome: 'PASSED', kind: 'UNCHANGED', baselineResultId: 'old-p', currentResultId: 'res-p' },
        { testName: 'Health', operationKey: 'GET /health', testCaseId: 't-3', baselineOutcome: 'SKIPPED', currentOutcome: 'SKIPPED', kind: 'UNCHANGED', baselineResultId: 'old-h', currentResultId: 'res-h' },
      ],
    }
    const baselines: (string | null)[] = []
    server.use(
      http.get(`${BASE}/runs`, () => HttpResponse.json(page([run(), comparison.baseline, run({ id: 'run-0', kind: 'AD_HOC' })]))),
      http.get(`${BASE}/runs/run-2/comparison`, ({ request }) => {
        const baseline = new URL(request.url).searchParams.get('baseline')
        baselines.push(baseline)
        return baseline === 'missing'
          ? HttpResponse.json({ title: 'Not found', status: 404, detail: 'Run not found', code: 'NOT_FOUND' }, { status: 404 })
          : HttpResponse.json(comparison)
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/runs/run-2/compare`)

    const counts = await screen.findByRole('list', { name: 'Change counts' })
    expect(within(counts).getByText('regressed').parentElement).toHaveTextContent('1 regressed')
    const changes = screen.getByRole('list', { name: 'Changes' })
    expect(within(changes).getAllByRole('listitem')).toHaveLength(1)
    expect(within(changes).getByRole('link', { name: 'Result in this run' })).toHaveAttribute('href', `/projects/${PROJECT.id}/runs/run-2?result=res-f`)

    await user.click(screen.getByRole('button', { name: 'Show 2 unchanged' }))
    expect(within(screen.getByRole('list', { name: 'Changes' })).getAllByRole('listitem')).toHaveLength(3)

    const picker = screen.getByLabelText('Baseline run')
    // Only other test runs can be baselines.
    expect(within(picker).getAllByRole('option')).toHaveLength(2)
    await user.selectOptions(picker, 'run-1')
    await waitFor(() => expect(baselines).toContain('run-1'))
  })

  it('says so when there is no earlier run to compare with', async () => {
    mockSpecApi({ versions: 1 })
    server.use(
      http.get(`${BASE}/runs`, () => HttpResponse.json(page([run()]))),
      http.get(`${BASE}/runs/run-2/comparison`, () =>
        HttpResponse.json({ title: 'Not found', status: 404, detail: 'Earlier run to compare with not found', code: 'NOT_FOUND' }, { status: 404 }),
      ),
    )
    renderApp(`/projects/${PROJECT.id}/runs/run-2/compare`)

    expect(await screen.findByText('There is no earlier test run in this project to compare with.')).toBeInTheDocument()
  })
})

describe('JSON paths', () => {
  it('match the backend format', () => {
    expect(childPath('$', 'items')).toBe('$.items')
    expect(childPath('$.items', 2)).toBe('$.items[2]')
    expect(childPath('$', 'first name')).toBe("$['first name']")
    expect(childPath('$', "it's")).toBe("$['it\\'s']")
    expect(childPath('$', '123')).toBe('$[123]')
  })

  it('round-trip through parsing', () => {
    for (const path of ['$', '$.items[2].price', "$['a/b'].x", "$['it\\'s'][0]"]) {
      const tokens = parsePath(path)
      expect(tokens).not.toBeNull()
      expect(toPath(tokens!)).toBe(path)
    }
    expect(parsePath('items.price')).toBeNull()
    expect(parsePath('$.items[x]')).toBeNull()
  })
})
