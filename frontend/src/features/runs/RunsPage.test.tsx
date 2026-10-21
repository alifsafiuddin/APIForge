import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockEnvironmentsApi, STAGING } from '@/features/environments/test-fixtures'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

import type { ResultDetail, ResultSummary, Run } from './api'

const BASE = `/api/v1/projects/${PROJECT.id}`

function run(overrides: Partial<Run> = {}): Run {
  return {
    id: 'run-1',
    projectId: PROJECT.id,
    kind: 'TEST_RUN',
    status: 'RUNNING',
    statusReason: null,
    environmentId: 'env-1',
    environmentName: 'Staging',
    baseUrl: 'http://127.0.0.1:8090',
    suiteId: null,
    specificationVersionId: 'v-1',
    triggeredBy: 'user-1',
    counts: { total: 2, passed: 1, failed: 0, inconclusive: 0, error: 0, skipped: 0 },
    createdAt: '2026-10-21T10:00:00Z',
    startedAt: '2026-10-21T10:00:01Z',
    finishedAt: null,
    ...overrides,
  }
}

const SUMMARY: ResultSummary = {
  id: 'res-1',
  testCaseId: 't-1',
  testName: 'Valid request',
  operationKey: 'GET /orders',
  outcome: 'PASSED',
  responseStatus: 200,
  durationMs: 12,
  errorKind: null,
  errorMessage: null,
  failedChecks: 0,
  skippedChecks: 1,
  firstFailure: null,
  executedAt: '2026-10-21T10:00:02Z',
}

const DETAIL: ResultDetail = {
  id: 'res-1',
  testRunId: 'run-1',
  testCaseId: 't-1',
  testName: 'Valid request',
  operationKey: 'GET /orders',
  outcome: 'PASSED',
  request: {
    method: 'GET',
    url: 'http://127.0.0.1:8090/orders?api_key=«redacted»',
    headers: { 'X-API-Key': '«redacted»', 'X-APIForge-Request-Id': 'corr-1' },
    body: { mode: 'NONE' },
    auth: 'ENVIRONMENT',
    correlationId: 'corr-1',
  },
  response: { status: 200, headers: { 'Content-Type': 'application/json' }, body: '{"items":[]}', sizeBytes: 12, truncated: false, contentType: 'application/json' },
  checks: [
    { name: 'STATUS', result: 'PASS', message: 'Status 200 is expected (200).' },
    { name: 'RESPONSE_SCHEMA', result: 'NOT_EVALUATED', message: 'Validation against the documented schema arrives with contract validation (not implemented yet).' },
  ],
  errorKind: null,
  errorMessage: null,
  durationMs: 12,
  timeToHeadersMs: 8,
  correlationId: 'corr-1',
  testCase: { name: 'Valid request' },
  executedAt: '2026-10-21T10:00:02Z',
}

describe('Runs', () => {
  it('starts a run of an endpoint from the Tests workspace and follows its progress', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const state = { starts: [] as unknown[], polls: 0, cancels: 0 }
    server.use(
      http.get(`${BASE}/tests/summary`, () =>
        HttpResponse.json({ total: 1, enabled: 1, stale: 0, operations: [{ operationKey: 'GET /orders', total: 1, enabled: 1, stale: 0 }] }),
      ),
      http.get(`${BASE}/tests`, () => HttpResponse.json({ content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } })),
      http.get(`${BASE}/tests/generation-plan`, () =>
        HttpResponse.json({ operationKey: 'GET /orders', specificationVersionId: 'v-1', generatorVersion: '1', scenarios: [], skipped: [] }),
      ),
      http.get(`${BASE}/test-suites`, () => HttpResponse.json([])),
      http.post(`${BASE}/runs`, async ({ request }) => {
        state.starts.push(await request.json())
        return HttpResponse.json(run({ status: 'QUEUED', counts: { ...run().counts, passed: 0 } }), { status: 202 })
      }),
      http.get(`${BASE}/runs/run-1`, () => {
        state.polls += 1
        return HttpResponse.json(state.cancels > 0 ? run({ status: 'CANCELLED', statusReason: 'Cancelled by the user; tests not yet executed were skipped.' }) : run())
      }),
      http.get(`${BASE}/runs/run-1/results`, () => HttpResponse.json({ content: [SUMMARY], page: { number: 0, size: 100, totalElements: 1, totalPages: 1 } })),
      http.get(`${BASE}/runs/run-1/results/res-1`, () => HttpResponse.json(DETAIL)),
      http.post(`${BASE}/runs/run-1/cancel`, () => {
        state.cancels += 1
        return HttpResponse.json(run(), { status: 202 })
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/tests?operation=${encodeURIComponent('GET /orders')}`)

    await user.click(await screen.findByRole('button', { name: 'Run' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Policy: Read-only/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }))

    await waitFor(() => expect(state.starts).toEqual([{ environmentId: 'env-1', operationKey: 'GET /orders' }]))
    expect(await screen.findByRole('heading', { name: 'Test run' })).toBeInTheDocument()
    expect(screen.getByRole('progressbar', { name: 'Run progress' })).toBeInTheDocument()
    expect(screen.getByText(/1 passed · 0 failed · 0 errors · 0 skipped of 2/)).toBeInTheDocument()

    await user.click(within(await screen.findByRole('list', { name: 'Results' })).getByRole('button', { name: /Valid request/ }))
    const sheet = await screen.findByRole('dialog')
    expect(await within(sheet).findByText('http://127.0.0.1:8090/orders?api_key=«redacted»', { exact: false })).toBeInTheDocument()
    expect(within(sheet).getByText('X-API-Key: «redacted»')).toBeInTheDocument()
    // A result recorded before contract validation: the unevaluated check is shown as such, never as a pass.
    const legacy = within(sheet).getByRole('row', { name: /RESPONSE_SCHEMA/ })
    expect(within(legacy).getByText('NOT EVALUATED')).toBeInTheDocument()
    expect(within(sheet).getByText('2 checks: 1 passed, 1 skipped (not validated)')).toBeInTheDocument()
    expect(within(sheet).getByText(/1 check was skipped and is not counted as passed/)).toBeInTheDocument()
    expect(within(sheet).getByText(/corr-1/, { selector: 'span' })).toBeInTheDocument()
    await user.keyboard('{Escape}')

    await user.click(screen.getByRole('button', { name: 'Cancel run' }))
    expect(await screen.findByText('Cancelled by the user; tests not yet executed were skipped.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Cancel run' })).not.toBeInTheDocument()
    expect(state.polls).toBeGreaterThan(1)
  })

  it('sends a console request and shows the redacted result', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const sent: unknown[] = []
    server.use(
      http.post(`${BASE}/executions`, async ({ request }) => {
        sent.push(await request.json())
        return HttpResponse.json({ ...DETAIL, outcome: 'FAILED', response: { ...DETAIL.response!, status: 500, body: '{"code":"BOOM"}' }, checks: [{ name: 'STATUS', result: 'FAIL', message: 'Expected 2XX, got 500.' }] })
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/console`)

    const path = await screen.findByLabelText('Path')
    await user.clear(path)
    await user.type(path, '/lab/status/500')
    await user.type(screen.getByLabelText('Query parameters (JSON object)'), '{{"a": ')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/JSON/)
    expect(sent).toHaveLength(0)

    await user.clear(screen.getByLabelText('Query parameters (JSON object)'))
    await user.click(screen.getByRole('button', { name: 'Send' }))

    const result = await screen.findByRole('region', { name: 'Checks' })
    expect(within(result).getByText('Expected 2XX, got 500.')).toBeInTheDocument()
    expect(screen.getByText('FAILED')).toBeInTheDocument()
    expect(sent).toEqual([
      expect.objectContaining({ environmentId: 'env-1', method: 'GET', path: '/lab/status/500', expectedStatuses: ['2XX'] }),
    ])
  })
})
