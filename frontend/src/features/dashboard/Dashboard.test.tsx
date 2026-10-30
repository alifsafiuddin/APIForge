import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { EMPTY_DASHBOARD, problem, server } from '@/test/server'

import type { Dashboard, ProjectSummary, RunRef } from './api'

const RUN: RunRef = {
  id: 'run-9',
  projectId: PROJECT.id,
  projectName: 'Inventory',
  status: 'COMPLETED',
  environmentName: 'Staging',
  total: 48,
  passed: 40,
  failed: 6,
  error: 2,
  inconclusive: 0,
  skipped: 0,
  createdAt: '2026-10-21T10:00:00Z',
  finishedAt: '2026-10-21T10:00:30Z',
}

const SUMMARY: ProjectSummary = {
  id: PROJECT.id,
  name: 'Inventory',
  description: 'Warehouse API',
  archived: false,
  updatedAt: '2026-10-21T10:00:00Z',
  activeSpecification: { id: 'v-3', versionNumber: 3, title: 'Inventory API', apiVersion: '1.2.0', operations: 12 },
  tests: { active: 48, enabled: 45, pendingReview: 3, stale: 2, suites: 2 },
  environments: 1,
  lastRun: RUN,
  coverage: {
    takenAt: '2026-10-21T10:00:31Z',
    operations: 12,
    operationsWithTests: 9,
    responses: 31,
    responsesExercised: 20,
    executed: 48,
    passed: 40,
  },
}

const DASHBOARD: Dashboard = {
  totals: {
    activeProjects: 2,
    archivedProjects: 1,
    runsLast7Days: 5,
    runsWithFailuresLast7Days: 3,
    runsInProgress: 0,
    testsPendingReview: 3,
    staleTests: 2,
  },
  projects: [
    SUMMARY,
    {
      ...SUMMARY,
      id: 'proj-2',
      name: 'Billing',
      description: null,
      activeSpecification: null,
      tests: { active: 0, enabled: 0, pendingReview: 0, stale: 0, suites: 0 },
      environments: 0,
      lastRun: null,
      coverage: null,
    },
  ],
  recentRuns: [RUN],
}

describe('Overview dashboard', () => {
  it('shows totals, project summaries and recent runs from the dashboard API', async () => {
    server.use(http.get('/api/v1/dashboard', () => HttpResponse.json(DASHBOARD)))
    renderApp('/')

    const totals = await screen.findByRole('region', { name: 'Totals' })
    expect(within(totals).getByText('Active projects').parentElement).toHaveTextContent('Active projects21 archived')
    expect(within(totals).getByText('Test runs, last 7 days').parentElement).toHaveTextContent('53 with failures or errors')
    expect(within(totals).getByText('Tests to review').parentElement).toHaveTextContent('3')

    const inventory = screen.getByRole('article', { name: 'Inventory' })
    expect(within(inventory).getByText('v3 · 12 operations')).toBeInTheDocument()
    expect(within(inventory).getByText('9/12 (75%)')).toBeInTheDocument()
    expect(within(inventory).getByText('40 of 48 passed, 6 failed, 2 errors')).toBeInTheDocument()
    expect(within(inventory).getByRole('link', { name: 'Inventory' })).toHaveAttribute('href', `/projects/${PROJECT.id}`)

    const billing = screen.getByRole('article', { name: 'Billing' })
    expect(within(billing).getByRole('link', { name: 'Import one' })).toHaveAttribute('href', '/projects/proj-2/spec')
    expect(within(billing).getByText('not measured yet')).toBeInTheDocument()
    expect(within(billing).getByText('No test runs yet.')).toBeInTheDocument()

    const runs = screen.getByRole('list', { name: 'Recent test runs' })
    expect(within(runs).getByRole('link', { name: /Inventory/ })).toHaveAttribute('href', `/projects/${PROJECT.id}/runs/run-9`)
  })

  it('offers to create the first project and opens it once created', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 0 })
    server.use(
      http.get('/api/v1/dashboard', () => HttpResponse.json(EMPTY_DASHBOARD)),
      http.post('/api/v1/projects', () => HttpResponse.json(PROJECT, { status: 201 })),
      http.get(`/api/v1/projects/${PROJECT.id}/overview`, () =>
        HttpResponse.json({
          project: { ...SUMMARY, activeSpecification: null, tests: { active: 0, enabled: 0, pendingReview: 0, stale: 0, suites: 0 }, environments: 0, lastRun: null, coverage: null },
          recentRuns: [],
        }),
      ),
    )
    const { router } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Create your first project' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Inventory')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    expect(await screen.findByText('Get this project testing')).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/projects/${PROJECT.id}`)
  })

  it('explains a failed load, offers a retry and keeps the system status visible', async () => {
    const user = userEvent.setup()
    let calls = 0
    server.use(
      http.get('/api/v1/dashboard', () => {
        calls += 1
        return calls === 1 ? problem(500, 'INTERNAL_ERROR', 'Unexpected error.') : HttpResponse.json(DASHBOARD)
      }),
    )
    renderApp('/')

    expect(await screen.findByText('The overview could not be loaded')).toBeInTheDocument()
    expect(screen.getByText('System status')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('article', { name: 'Inventory' })).toBeInTheDocument()
  })
})

describe('Project overview', () => {
  it('lists the next setup step and real numbers, and only allows a run when it can start', async () => {
    mockSpecApi({ versions: 1 })
    server.use(
      http.get(`/api/v1/projects/${PROJECT.id}/overview`, () =>
        HttpResponse.json({ project: { ...SUMMARY, environments: 0, lastRun: null }, recentRuns: [] }),
      ),
    )
    renderApp(`/projects/${PROJECT.id}`)

    expect(await screen.findByText('2 of 4 steps done.')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add an environment' })).toHaveAttribute('href', `/projects/${PROJECT.id}/environments`)
    const numbers = screen.getByRole('region', { name: 'Project numbers' })
    expect(within(numbers).getByRole('link', { name: /Active tests/ })).toHaveTextContent('45 enabled · 3 to review · 2 stale')
    expect(within(numbers).getByRole('link', { name: /Operations with tests/ })).toHaveTextContent('75%')
    expect(screen.getByRole('button', { name: 'Run tests' })).toBeDisabled()
    expect(screen.getByText('Runs appear here once tests and an environment exist.')).toBeInTheDocument()
    const sections = screen.getByRole('navigation', { name: 'Project sections' })
    expect(within(sections).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows recent runs and hides the checklist once the project is set up', async () => {
    mockSpecApi({ versions: 1 })
    server.use(
      http.get(`/api/v1/projects/${PROJECT.id}/overview`, () => HttpResponse.json({ project: SUMMARY, recentRuns: [RUN] })),
    )
    renderApp(`/projects/${PROJECT.id}`)

    const runs = await screen.findByRole('list', { name: 'Recent test runs' })
    expect(within(runs).getByRole('link')).toHaveAttribute('href', `/projects/${PROJECT.id}/runs/run-9`)
    expect(screen.queryByText('Get this project testing')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Run tests' })).toBeEnabled()
  })
})
