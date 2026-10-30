import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { describe, expect, it, vi } from 'vitest'

import { mockEnvironmentsApi, STAGING } from '@/features/environments/test-fixtures'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { problem, server } from '@/test/server'

import { createQueryClient } from './query-client'
import { RouteErrorPage } from './RouteErrorPage'

/** How API failures surface across workflows: problem details shown, sessions ended, nothing swallowed. */
describe('API error handling', () => {
  it('shows the problem detail when generation fails and keeps the action available', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    server.use(
      http.get(`/api/v1/projects/${PROJECT.id}/tests/summary`, () =>
        HttpResponse.json({ total: 0, enabled: 0, stale: 0, operations: [] }),
      ),
      http.get(`/api/v1/projects/${PROJECT.id}/test-suites`, () => HttpResponse.json([])),
      http.post(`/api/v1/projects/${PROJECT.id}/tests/generate`, () =>
        problem(409, 'NO_ACTIVE_SPECIFICATION', 'Activate a specification version first.'),
      ),
    )
    renderApp(`/projects/${PROJECT.id}/tests`)

    const generate = await screen.findAllByRole('button', { name: 'Generate tests' })
    await user.click(generate[0]!)

    expect(await screen.findByText('Activate a specification version first.')).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: 'Generate tests' })[0]).toBeEnabled()
  })

  it('reports a refused run start (rate limit) without leaving the dialog', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    server.use(
      http.get(`/api/v1/projects/${PROJECT.id}/overview`, () =>
        HttpResponse.json({
          project: {
            id: PROJECT.id,
            name: 'Inventory',
            description: null,
            archived: false,
            updatedAt: '2026-10-21T10:00:00Z',
            activeSpecification: { id: 'v-1', versionNumber: 1, title: 'Inventory API', apiVersion: null, operations: 2 },
            tests: { active: 3, enabled: 3, pendingReview: 0, stale: 0, suites: 1 },
            environments: 1,
            lastRun: null,
            coverage: null,
          },
          recentRuns: [],
        }),
      ),
      http.get(`/api/v1/projects/${PROJECT.id}/test-suites`, () => HttpResponse.json([])),
      http.post(`/api/v1/projects/${PROJECT.id}/runs`, () =>
        problem(429, 'RATE_LIMITED', 'You already have 2 runs in progress; wait for one to finish.'),
      ),
    )
    renderApp(`/projects/${PROJECT.id}`)

    await user.click(await screen.findByRole('button', { name: 'Run tests' }))
    await user.click(await screen.findByRole('button', { name: 'Start run' }))

    expect(await screen.findByText('You already have 2 runs in progress; wait for one to finish.')).toBeInTheDocument()
    expect(screen.getByRole('dialog', { name: 'Run tests' })).toBeInTheDocument()
  })

  it('ends the session when a mutation answers 401 and returns to the sign-in page', async () => {
    const user = userEvent.setup()
    server.use(
      http.post('/api/v1/projects', () => problem(401, 'UNAUTHENTICATED', 'Authentication is required.')),
    )
    const { router } = renderApp('/projects')

    await user.click((await screen.findAllByRole('button', { name: 'New project' }))[0]!)
    await user.type(await screen.findByLabelText('Name'), 'Expired session')
    server.use(http.get('/api/v1/auth/me', () => problem(401, 'UNAUTHENTICATED', 'Authentication is required.')))
    await user.click(screen.getByRole('button', { name: 'Create project' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  })

  it('shows a responses that do not match the expected shape as an error, not as data', async () => {
    server.use(http.get('/api/v1/dashboard', () => HttpResponse.json({ totals: { activeProjects: 'many' } })))
    renderApp('/')

    expect(await screen.findByText('The backend returned a response in an unexpected format.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Totals' })).not.toBeInTheDocument()
  })

  it('renders the error page when a route throws while rendering', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    function Broken(): never {
      throw new Error('render failure')
    }
    const router = createMemoryRouter([{ path: '/', element: <Broken />, errorElement: <RouteErrorPage /> }])
    render(
      <QueryClientProvider client={createQueryClient()}>
        <RouterProvider router={router} />
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: 'Something went wrong' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to overview' })).toHaveAttribute('href', '/')
  })
})
