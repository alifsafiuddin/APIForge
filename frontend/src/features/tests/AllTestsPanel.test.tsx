import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockEnvironmentsApi, STAGING } from '@/features/environments/test-fixtures'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

import type { TestSummary } from './api'

const BASE = `/api/v1/projects/${PROJECT.id}`

function test(id: string, name: string, overrides: Partial<TestSummary> = {}): TestSummary {
  return {
    id,
    suiteId: 'suite-gen',
    operationKey: 'GET /orders',
    method: 'GET',
    path: '/orders',
    name,
    category: 'HAPPY_PATH',
    origin: 'DETERMINISTIC',
    reviewStatus: 'APPROVED',
    lifecycleStatus: 'ACTIVE',
    riskLevel: 'READ_ONLY',
    enabled: true,
    userModified: false,
    expectedStatuses: ['200'],
    sortOrder: 0,
    updatedAt: '2026-10-21T10:00:00Z',
    version: 0,
    ...overrides,
  }
}

const TESTS = [
  test('t-1', 'Valid request'),
  test('t-2', 'Missing api key', { category: 'AUTH', expectedStatuses: ['401'] }),
  test('t-3', 'Suggested: limit above maximum', { origin: 'AI_SUGGESTED', reviewStatus: 'PENDING_REVIEW' }),
]

/** Applies q (name contains), reviewStatus, origin and page like the backend. */
function mockTests() {
  const queries: URLSearchParams[] = []
  server.use(
    http.get(`${BASE}/tests/summary`, () =>
      HttpResponse.json({ total: 3, enabled: 3, stale: 0, operations: [{ operationKey: 'GET /orders', total: 3, enabled: 3, stale: 0 }] }),
    ),
    http.get(`${BASE}/tests`, ({ request }) => {
      const params = new URL(request.url).searchParams
      queries.push(params)
      const q = params.get('q')?.toLowerCase()
      const content = TESTS.filter(
        (candidate) =>
          (!q || candidate.name.toLowerCase().includes(q)) &&
          (!params.get('reviewStatus') || candidate.reviewStatus === params.get('reviewStatus')) &&
          (!params.get('origin') || candidate.origin === params.get('origin')),
      )
      return HttpResponse.json({ content, page: { number: 0, size: 25, totalElements: content.length, totalPages: 1 } })
    }),
    http.get(`${BASE}/test-suites`, () => HttpResponse.json([])),
  )
  return queries
}

describe('Project-wide test finder', () => {
  it('searches by name and shows the review queue, keeping both in the URL', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const queries = mockTests()
    const { router } = renderApp(`/projects/${PROJECT.id}/tests`)

    const list = await screen.findByRole('list', { name: 'Matching tests' })
    expect(within(list).getByText('3 tests')).toBeInTheDocument()

    await user.type(screen.getByLabelText('Search tests by name'), 'api key')
    await waitFor(() => expect(within(screen.getByRole('list', { name: 'Matching tests' })).getByText('1 test')).toBeInTheDocument())
    expect(queries.at(-1)?.get('q')).toBe('api key')
    expect(new URLSearchParams(router.state.location.search).get('find')).toBe('api key')

    await user.clear(screen.getByLabelText('Search tests by name'))
    await user.click(screen.getByRole('button', { name: 'Needs review' }))
    await waitFor(() => expect(queries.at(-1)?.get('reviewStatus')).toBe('PENDING_REVIEW'))
    const queue = await screen.findByRole('list', { name: 'Matching tests' })
    await waitFor(() => expect(within(queue).getByText('Suggested: limit above maximum')).toBeInTheDocument())
    expect(screen.getByRole('button', { name: 'Needs review' })).toHaveAttribute('aria-pressed', 'true')
    expect(new URLSearchParams(router.state.location.search).get('view')).toBe('review')
  })

  it('opens the review queue from the URL', async () => {
    mockSpecApi({ versions: 1 })
    const queries = mockTests()
    renderApp(`/projects/${PROJECT.id}/tests?view=review`)

    expect(await screen.findByText('Suggested: limit above maximum')).toBeInTheDocument()
    expect(queries.at(-1)?.get('reviewStatus')).toBe('PENDING_REVIEW')
  })
})
