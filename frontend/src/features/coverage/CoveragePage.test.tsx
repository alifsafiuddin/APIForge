import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockEnvironmentsApi, STAGING } from '@/features/environments/test-fixtures'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { server } from '@/test/server'

import type { CoverageReport, OperationCoverage, OperationRow, VersionChanges } from './api'
import { percent } from './format'

const BASE = `/api/v1/projects/${PROJECT.id}/coverage`
const VERSION = { id: 'v-2', number: 2, title: 'Shop', apiVersion: '1.1.0', importedAt: '2026-10-22T08:00:00Z' }
const NONE = { tests: 0, executed: 0, passed: 0, failed: 0, error: 0, inconclusive: 0, skipped: 0, notRun: 0 }

function row(overrides: Partial<OperationRow>): OperationRow {
  return {
    operationKey: 'GET /items',
    method: 'GET',
    path: '/items',
    tags: ['items'],
    deprecated: false,
    tests: 3,
    duplicates: 0,
    inactiveTests: 0,
    responses: 2,
    responsesTargeted: 2,
    responsesExercised: 2,
    constraints: 0,
    constraintsCovered: 0,
    branches: 0,
    branchesExercised: 0,
    missingNegatives: [],
    undocumentedStatuses: [],
    outcomes: { ...NONE, tests: 3, executed: 3, passed: 3 },
    ...overrides,
  }
}

const REPORT: CoverageReport = {
  version: VERSION,
  environmentId: null,
  metrics: [
    { id: 'operations', group: 'TEST_DESIGN', label: 'Discovered operations', value: 3, total: null, definition: 'Operations in the active version.' },
    { id: 'operationsWithTests', group: 'TEST_DESIGN', label: 'Operations with tests', value: 2, total: 3, definition: 'At least one enabled test.' },
    { id: 'constraintsCovered', group: 'TEST_DESIGN', label: 'Request constraints covered', value: 7, total: 9, definition: 'Constraints with a test.' },
    { id: 'executed', group: 'EXECUTION', label: 'Tests executed', value: 6, total: 8, definition: 'Distinct tests with an attempt.' },
    { id: 'passRate', group: 'EXECUTION', label: 'Passed of executed', value: 5, total: 6, definition: 'Not coverage.' },
  ],
  outcomes: { tests: 8, executed: 6, passed: 5, failed: 1, error: 0, inconclusive: 0, skipped: 1, notRun: 1 },
  operations: [
    row({}),
    row({
      operationKey: 'POST /items',
      method: 'POST',
      path: '/items',
      tests: 5,
      duplicates: 1,
      responses: 4,
      responsesTargeted: 4,
      responsesExercised: 3,
      constraints: 9,
      constraintsCovered: 7,
      branches: 2,
      branchesExercised: 1,
      missingNegatives: ['UNSUPPORTED_CONTENT_TYPE'],
      undocumentedStatuses: ['500'],
      outcomes: { ...NONE, tests: 5, executed: 3, passed: 2, failed: 1, skipped: 1, notRun: 1 },
    }),
    row({ operationKey: 'DELETE /items/{id}', method: 'DELETE', path: '/items/{id}', tags: ['admin'], tests: 0, responsesTargeted: 0, responsesExercised: 0, outcomes: NONE }),
  ],
  gaps: {
    operationsWithoutTests: ['DELETE /items/{id}'],
    responsesNotExercised: [
      { operationKey: 'POST /items', status: '415', targeted: true },
      { operationKey: 'DELETE /items/{id}', status: '204', targeted: false },
    ],
    missingNegatives: [{ kind: 'UNSUPPORTED_CONTENT_TYPE', label: 'Unsupported content type', operations: ['POST /items'] }],
  },
}

const DETAIL: OperationCoverage = {
  version: VERSION,
  summary: REPORT.operations[1]!,
  tests: [
    { id: 't-1', name: 'Valid item', scenarioKey: 'happy.minimal', kind: 'VALID_REQUEST', duplicateOf: null, latestOutcome: 'PASSED', latestStatus: 201, lastExecutedAt: '2026-10-22T09:00:00Z' },
    { id: 't-9', name: 'Copy of valid item', scenarioKey: null, kind: null, duplicateOf: 't-1', latestOutcome: null, latestStatus: null, lastExecutedAt: null },
  ],
  scenarios: [
    { scenarioKey: 'happy.minimal', name: 'Valid request', kind: 'VALID_REQUEST', covered: true, testIds: ['t-1', 't-9'] },
    { scenarioKey: 'content-type.unsupported', name: 'Unsupported content type text/plain', kind: 'UNSUPPORTED_CONTENT_TYPE', covered: false, testIds: [] },
  ],
  skipped: [{ scenario: 'Field scenarios inside field delivery', reason: 'oneOf is not mutated' }],
  responses: [
    { status: '201', targeted: true, exercised: true, receivedStatuses: [201] },
    { status: '415', targeted: true, exercised: false, receivedStatuses: [] },
  ],
  branches: [
    { id: 'delivery#oneOf/0', path: '$.delivery', label: 'delivery: one of Address', exercised: true },
    { id: 'delivery#oneOf/1', path: '$.delivery', label: 'delivery: one of Pickup', exercised: false },
  ],
  negatives: [
    { kind: 'AUTHENTICATION', label: 'Missing or invalid credentials', state: 'PRESENT' },
    { kind: 'UNSUPPORTED_CONTENT_TYPE', label: 'Unsupported content type', state: 'MISSING' },
    { kind: 'INVALID_VALUE', label: 'Value outside the allowed set', state: 'NOT_APPLICABLE' },
  ],
}

describe('Coverage', () => {
  it('separates test design from execution, defines every metric, lists gaps and filters operations', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const requested: (string | null)[] = []
    server.use(
      http.get(BASE, ({ request }) => {
        requested.push(new URL(request.url).searchParams.get('environmentId'))
        return HttpResponse.json(REPORT)
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/coverage`)

    expect(await screen.findByText(/this is not code coverage/)).toBeInTheDocument()
    const design = screen.getByRole('region', { name: 'Test design' })
    const execution = screen.getByRole('region', { name: 'Execution' })
    expect(within(design).getByText('Operations with tests')).toBeInTheDocument()
    expect(within(design).getByText('At least one enabled test.')).toBeInTheDocument()
    expect(within(design).getByText('78%')).toBeInTheDocument()
    expect(within(design).queryByText('Passed of executed')).not.toBeInTheDocument()
    expect(within(execution).getByText('Passed of executed')).toBeInTheDocument()
    expect(within(execution).getByText(/A pass rate says how the API behaved, not how much is covered/)).toBeInTheDocument()
    expect(within(execution).getByText('1 not run')).toBeInTheDocument()

    const gaps = screen.getByRole('region', { name: 'Gaps' })
    expect(within(gaps).getByText('Endpoints without tests (1)')).toBeInTheDocument()
    expect(within(gaps).getByText('no test expects it')).toBeInTheDocument()
    expect(within(gaps).getByText(/Unsupported content type — 1 operation/)).toBeInTheDocument()

    const table = screen.getByRole('table')
    expect(within(table).getAllByRole('row')).toHaveLength(4)
    await user.selectOptions(screen.getByLabelText('Filter by gap'), 'NO_TESTS')
    expect(within(table).getAllByRole('row')).toHaveLength(2)
    expect(within(table).getByText('/items/{id}')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Filter by gap'), 'UNDOCUMENTED')
    expect(within(table).getByText('Undocumented: 500')).toBeInTheDocument()
    await user.selectOptions(screen.getByLabelText('Filter by gap'), 'ALL')
    await user.selectOptions(screen.getByLabelText('Filter by tag'), 'admin')
    expect(within(table).getAllByRole('row')).toHaveLength(2)
    await user.selectOptions(screen.getByLabelText('Filter by tag'), '')
    await user.type(screen.getByLabelText('Search operations'), 'post')
    expect(within(table).getAllByRole('row')).toHaveLength(2)
    expect(within(table).getByText('+1 duplicate')).toBeInTheDocument()

    await user.selectOptions(screen.getByLabelText('Runs to include'), STAGING.id)
    await waitFor(() => expect(requested).toContain(STAGING.id))
  })

  it('drills down into one operation', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([])
    let requestedKey: string | null = null
    server.use(
      http.get(BASE, () => HttpResponse.json(REPORT)),
      http.get(`${BASE}/operation`, ({ request }) => {
        requestedKey = new URL(request.url).searchParams.get('key')
        return HttpResponse.json(DETAIL)
      }),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/coverage`)

    await user.click(within(await screen.findByRole('region', { name: 'Gaps' })).getAllByRole('button', { name: 'POST /items' })[0]!)
    const sheet = await screen.findByRole('dialog')
    expect(requestedKey).toBe('POST /items')
    const responses = await within(sheet).findByRole('region', { name: 'Documented responses' })
    expect(within(responses).getByRole('row', { name: /415/ })).toHaveTextContent('not yet')
    expect(within(sheet).getByText(/undocumented status 500/)).toBeInTheDocument()
    expect(within(sheet).getByText('Unsupported content type — missing')).toBeInTheDocument()
    expect(within(sheet).getByText('Value outside the allowed set (does not apply)')).toBeInTheDocument()
    const scenarios = within(sheet).getByRole('list', { name: 'Scenarios' })
    expect(within(scenarios).getAllByRole('listitem')).toHaveLength(2)
    await user.click(within(sheet).getByRole('button', { name: 'Only missing' }))
    expect(within(scenarios).getAllByRole('listitem')).toHaveLength(1)
    expect(within(sheet).getByText('Not generated automatically (1)')).toBeInTheDocument()
    expect(within(sheet).getByText('delivery: one of Pickup')).toBeInTheDocument()
    expect(within(sheet).getByText(/Same request as “Valid item”; counted once/)).toBeInTheDocument()
    expect(within(sheet).getByText('NOT RUN')).toBeInTheDocument()
    expect(within(sheet).getByRole('link', { name: 'open in Tests' })).toHaveAttribute(
      'href',
      `/projects/${PROJECT.id}/tests?operation=${encodeURIComponent('POST /items')}`,
    )
  })

  it('shows what changed since the previous version and how coverage moved', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([])
    const changes: VersionChanges = {
      from: { ...VERSION, id: 'v-1', number: 1 },
      to: VERSION,
      operations: [
        { operationKey: 'PUT /items/{id}', kind: 'ADDED', aspects: [], tests: 0, testsFromEarlierVersion: 0, staleTests: 0 },
        { operationKey: 'DELETE /items/{id}', kind: 'REMOVED', aspects: [], tests: 0, testsFromEarlierVersion: 0, staleTests: 4 },
        { operationKey: 'POST /items', kind: 'CHANGED', aspects: ['responses'], tests: 5, testsFromEarlierVersion: 5, staleTests: 0 },
      ],
      unchanged: 2,
      baseline: {
        id: 's-1',
        specificationVersionId: 'v-1',
        versionNumber: 1,
        trigger: 'RUN_FINISHED',
        runId: 'r-1',
        takenAt: '2026-10-21T10:00:00Z',
        metrics: { operations: 4, operationsWithTests: 4, tests: 30, executed: 30, passed: 29 },
      },
      current: { operations: 4, operationsWithTests: 3, tests: 25, executed: 0, passed: 0 },
      history: [
        { id: 's-2', specificationVersionId: 'v-2', versionNumber: 2, trigger: 'VERSION_ACTIVATED', runId: null, takenAt: '2026-10-22T08:00:00Z', metrics: { operations: 4, operationsWithTests: 3, tests: 25, executed: 0, passed: 0 } },
      ],
    }
    server.use(
      http.get(BASE, () => HttpResponse.json(REPORT)),
      http.get(`${BASE}/changes`, () => HttpResponse.json(changes)),
    )
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/coverage`)

    await user.click(await screen.findByRole('button', { name: 'Version changes' }))
    const list = await screen.findByRole('list', { name: 'Operation changes' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(3)
    expect(within(list).getByText('no enabled tests yet')).toBeInTheDocument()
    expect(within(list).getByText('4 tests kept as stale')).toBeInTheDocument()
    expect(within(list).getByText(/5 generated from an earlier version \(regenerate to update\)/)).toBeInTheDocument()
    expect(screen.getByText(/3 changed, 2 unchanged/)).toBeInTheDocument()
    const operationsRow = screen.getByRole('row', { name: /^Operations with tests/ })
    expect(within(operationsRow).getAllByRole('cell').map((cell) => cell.textContent)).toEqual(['Operations with tests', '4', '3', '-1'])
    expect(screen.getByText('Version activated')).toBeInTheDocument()
  })

  it('explains that coverage needs a specification', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([])
    server.use(
      http.get(BASE, () =>
        HttpResponse.json({ title: 'Conflict', status: 409, detail: 'Import a specification to see its coverage.', code: 'NO_ACTIVE_SPECIFICATION' }, { status: 409 }),
      ),
    )
    renderApp(`/projects/${PROJECT.id}/coverage`)

    expect(await screen.findByText('No specification yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Import one' })).toHaveAttribute('href', `/projects/${PROJECT.id}/spec`)
  })
})

describe('percent', () => {
  it('never rounds to 100% or 0% unless it is', () => {
    expect(percent(0, 0)).toBe('–')
    expect(percent(0, 5)).toBe('0%')
    expect(percent(1, 1000)).toBe('<1%')
    expect(percent(999, 1000)).toBe('99%')
    expect(percent(7, 9)).toBe('78%')
    expect(percent(3, 3)).toBe('100%')
  })
})
