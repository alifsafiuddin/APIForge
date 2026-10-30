import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockEnvironmentsApi, STAGING } from '@/features/environments/test-fixtures'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

import type { Run } from './api'

const BASE = `/api/v1/projects/${PROJECT.id}`

function run(id: string, overrides: Partial<Run> = {}): Run {
  return {
    id,
    projectId: PROJECT.id,
    kind: 'TEST_RUN',
    status: 'COMPLETED',
    statusReason: null,
    environmentId: 'env-1',
    environmentName: 'Staging',
    baseUrl: 'http://127.0.0.1:8090',
    suiteId: null,
    specificationVersionId: 'v-1',
    triggeredBy: 'user-1',
    counts: { total: 2, passed: 2, failed: 0, inconclusive: 0, error: 0, skipped: 0 },
    createdAt: '2026-10-21T10:00:00Z',
    startedAt: '2026-10-21T10:00:01Z',
    finishedAt: '2026-10-21T10:00:02Z',
    ...overrides,
  }
}

const RUNS = [
  run('run-1'),
  run('run-2', { counts: { total: 2, passed: 1, failed: 1, inconclusive: 0, error: 0, skipped: 0 } }),
  run('run-3', { kind: 'AD_HOC', counts: { total: 1, passed: 1, failed: 0, inconclusive: 0, error: 0, skipped: 0 } }),
]

/** Filters like the backend: kind, status and failing (true = failed or errored tests). */
function mockRuns() {
  const queries: URLSearchParams[] = []
  server.use(
    http.get(`${BASE}/runs`, ({ request }) => {
      const params = new URL(request.url).searchParams
      queries.push(params)
      const failing = params.get('failing')
      const content = RUNS.filter(
        (candidate) =>
          (!params.get('kind') || candidate.kind === params.get('kind')) &&
          (!params.get('status') || candidate.status === params.get('status')) &&
          (failing === null || (candidate.counts.failed + candidate.counts.error > 0) === (failing === 'true')),
      )
      return HttpResponse.json({ content, page: { number: 0, size: 20, totalElements: content.length, totalPages: content.length ? 1 : 0 } })
    }),
  )
  return queries
}

describe('Run history filters', () => {
  it('filters by kind and result, keeps the filters in the URL and can clear them', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    const queries = mockRuns()
    const { router } = renderApp(`/projects/${PROJECT.id}/runs`)

    expect(await screen.findByText('3 runs')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Kind'), 'Test runs')
    expect(await screen.findByText('2 runs')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Result'), 'With failures or errors')

    const list = await screen.findByRole('list', { name: 'Runs' })
    await waitFor(() => expect(within(list).getAllByRole('link')).toHaveLength(1))
    expect(within(list).getByRole('link')).toHaveAttribute('href', `/projects/${PROJECT.id}/runs/run-2`)
    expect(queries.at(-1)?.get('kind')).toBe('TEST_RUN')
    expect(queries.at(-1)?.get('failing')).toBe('true')
    expect(router.state.location.search).toBe('?kind=TEST_RUN&result=failing')

    await user.selectOptions(screen.getByLabelText('Status'), 'Running')
    expect(await screen.findByText('No runs match these filters.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(await screen.findByText('3 runs')).toBeInTheDocument()
    expect(router.state.location.search).toBe('')
  })

  it('restores filters from the URL after a reload', async () => {
    mockSpecApi({ versions: 1 })
    const queries = mockRuns()
    renderApp(`/projects/${PROJECT.id}/runs?kind=AD_HOC`)

    expect(await screen.findByText('1 run')).toBeInTheDocument()
    expect(screen.getByLabelText('Kind')).toHaveValue('AD_HOC')
    expect(queries.at(-1)?.get('kind')).toBe('AD_HOC')
  })
})

describe('Run dialog', () => {
  it('runs all enabled tests of a chosen suite', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const bodies: unknown[] = []
    server.use(
      http.get(`${BASE}/overview`, () =>
        HttpResponse.json({
          project: {
            id: PROJECT.id,
            name: 'Inventory',
            description: null,
            archived: false,
            updatedAt: '2026-10-21T10:00:00Z',
            activeSpecification: { id: 'v-1', versionNumber: 1, title: 'Inventory API', apiVersion: null, operations: 2 },
            tests: { active: 5, enabled: 5, pendingReview: 0, stale: 0, suites: 2 },
            environments: 1,
            lastRun: null,
            coverage: null,
          },
          recentRuns: [],
        }),
      ),
      http.get(`${BASE}/test-suites`, () =>
        HttpResponse.json([
          { id: 'suite-gen', name: 'Generated', description: null, kind: 'GENERATED', testCount: 4, createdAt: '2026-10-21T10:00:00Z', updatedAt: '2026-10-21T10:00:00Z', version: 0 },
          { id: 'suite-smoke', name: 'Smoke', description: null, kind: 'CUSTOM', testCount: 1, createdAt: '2026-10-21T10:00:00Z', updatedAt: '2026-10-21T10:00:00Z', version: 0 },
        ]),
      ),
      http.post(`${BASE}/runs`, async ({ request }) => {
        bodies.push(await request.json())
        return HttpResponse.json(run('run-new', { status: 'QUEUED' }), { status: 202 })
      }),
      http.get(`${BASE}/runs/run-new`, () => HttpResponse.json(run('run-new'))),
      http.get(`${BASE}/runs/run-new/results`, () =>
        HttpResponse.json({ content: [], page: { number: 0, size: 100, totalElements: 0, totalPages: 0 } }),
      ),
    )
    const { router } = renderApp(`/projects/${PROJECT.id}`)

    await user.click(await screen.findByRole('button', { name: 'Run tests' }))
    const dialog = await screen.findByRole('dialog', { name: 'Run tests' })
    await user.selectOptions(await within(dialog).findByLabelText('Suite to run'), 'Smoke (1)')
    expect(within(dialog).getByLabelText('All enabled tests of suite')).toBeChecked()
    await user.click(within(dialog).getByRole('button', { name: 'Start run' }))

    await waitFor(() => expect(router.state.location.pathname).toBe(`/projects/${PROJECT.id}/runs/run-new`))
    expect(bodies).toEqual([{ environmentId: 'env-1', suiteId: 'suite-smoke' }])
  })
})
