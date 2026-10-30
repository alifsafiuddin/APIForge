import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { problem, server } from '@/test/server'

import type { GenerationReport, Suite, TestDetail, TestSummary } from './api'

const BASE = `/api/v1/projects/${PROJECT.id}`
const PAGE = `/projects/${PROJECT.id}/tests`

function summary(id: string, overrides: Partial<TestSummary> = {}): TestSummary {
  return {
    id,
    suiteId: 'suite-gen',
    operationKey: 'POST /orders',
    method: 'POST',
    path: '/orders',
    name: `Test ${id}`,
    category: 'MISSING_FIELD',
    origin: 'DETERMINISTIC',
    reviewStatus: 'APPROVED',
    lifecycleStatus: 'ACTIVE',
    riskLevel: 'MUTATING',
    enabled: true,
    userModified: false,
    expectedStatuses: ['400', '422'],
    sortOrder: 0,
    updatedAt: '2026-10-21T10:00:00Z',
    version: 3,
    ...overrides,
  }
}

function detail(test: TestSummary): TestDetail {
  return {
    id: test.id,
    suiteId: test.suiteId,
    operationKey: test.operationKey,
    method: test.method,
    path: test.path,
    name: test.name,
    category: test.category,
    origin: test.origin,
    reviewStatus: test.reviewStatus,
    lifecycleStatus: test.lifecycleStatus,
    riskLevel: test.riskLevel,
    enabled: test.enabled,
    userModified: test.userModified,
    updatedAt: test.updatedAt,
    executable: test.enabled && test.reviewStatus === 'APPROVED',
    scenarioKey: 'body.missing:/customerId',
    specLocation: '#/components/schemas/NewOrder/required',
    rationale: "'customerId' is listed in 'required' of schema NewOrder. The request omits it.",
    generatorVersion: '1',
    sourceSpecificationVersionId: 'v-1',
    request: {
      pathParams: {},
      query: {},
      headers: {},
      body: { mode: 'JSON', contentType: 'application/json', value: { lines: [{ sku: 'A-1', quantity: 1 }] } },
      auth: 'ENVIRONMENT',
    },
    expectations: {
      statuses: test.expectedStatuses,
      summary: 'Rejected with 400 or 422, the documented client-error responses for invalid input.',
      validateResponseSchema: true,
      responseChecks: [{ status: '400', mediaType: 'application/json', schemaName: 'Error' }],
    },
    reviewedBy: null,
    reviewedAt: null,
    createdAt: '2026-10-21T10:00:00Z',
    version: test.version,
  }
}

const REPORT: GenerationReport = {
  specificationVersionId: 'v-1',
  specificationVersionNumber: 1,
  operations: 3,
  created: 2,
  updated: 0,
  unchanged: 0,
  keptUserEdits: 0,
  archived: 0,
  total: 2,
  generatorVersion: '1',
  skipped: [{ operationKey: 'DELETE /products/{sku}', scenario: "Missing path parameter 'sku'", reason: 'not representable' }],
}

const SUITES: Suite[] = [
  {
    id: 'suite-gen',
    name: 'Generated tests',
    description: null,
    kind: 'GENERATED',
    testCount: 2,
    createdAt: '2026-10-21T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    version: 0,
  },
  {
    id: 'suite-smoke',
    name: 'Smoke',
    description: null,
    kind: 'CUSTOM',
    testCount: 0,
    createdAt: '2026-10-21T10:00:00Z',
    updatedAt: '2026-10-21T10:00:00Z',
    version: 0,
  },
]

/** Mocks the tests API with an in-memory store; returns the recorded writes. */
function mockTestsApi({ generated = true }: { generated?: boolean } = {}) {
  mockSpecApi({ versions: 1 })
  const state = {
    tests: generated
      ? [summary('t-1', { name: "Missing required field 'customerId'" }), summary('t-2', { name: 'Valid request', category: 'HAPPY_PATH', expectedStatuses: ['201'] })]
      : ([] as TestSummary[]),
    generations: 0,
    patches: [] as unknown[],
    bulk: [] as unknown[],
    edits: [] as unknown[],
    suites: [...SUITES],
    deletedSuites: [] as string[],
    reviews: [] as unknown[],
  }
  server.use(
    http.get(`${BASE}/tests/summary`, () => {
      const enabled = state.tests.filter((test) => test.enabled).length
      return HttpResponse.json({
        total: state.tests.length,
        enabled,
        stale: 0,
        operations: state.tests.length
          ? [{ operationKey: 'POST /orders', total: state.tests.length, enabled, stale: 0 }]
          : [],
      })
    }),
    http.get(`${BASE}/tests`, ({ request }) => {
      const params = new URL(request.url).searchParams
      const content = state.tests.filter(
        (test) =>
          (!params.get('operationKey') || test.operationKey === params.get('operationKey')) &&
          (!params.get('category') || test.category === params.get('category')) &&
          (!params.get('enabled') || String(test.enabled) === params.get('enabled')),
      )
      return HttpResponse.json({ content, page: { number: 0, size: 100, totalElements: content.length, totalPages: 1 } })
    }),
    http.get(`${BASE}/tests/generation-plan`, () =>
      HttpResponse.json({
        operationKey: 'POST /orders',
        specificationVersionId: 'v-1',
        generatorVersion: '1',
        scenarios: [],
        skipped: [{ operationKey: 'POST /orders', scenario: "Field scenarios inside field 'delivery'", reason: 'it is a oneOf/anyOf composition' }],
      }),
    ),
    http.get(`${BASE}/tests/:testId`, ({ params }) => {
      const test = state.tests.find((candidate) => candidate.id === params.testId)
      return test ? HttpResponse.json(detail(test)) : problem(404, 'NOT_FOUND', 'Test not found.')
    }),
    http.post(`${BASE}/tests/generate`, () => {
      state.generations += 1
      state.tests = [summary('t-1', { name: "Missing required field 'customerId'" }), summary('t-2', { name: 'Valid request', category: 'HAPPY_PATH', expectedStatuses: ['201'] })]
      return HttpResponse.json(REPORT)
    }),
    http.patch(`${BASE}/tests/:testId`, async ({ params, request }) => {
      const body = (await request.json()) as { enabled?: boolean; version: number }
      state.patches.push({ id: params.testId, ...body })
      state.tests = state.tests.map((test) =>
        test.id === params.testId ? { ...test, enabled: body.enabled ?? test.enabled, version: test.version + 1 } : test,
      )
      return HttpResponse.json(detail(state.tests.find((test) => test.id === params.testId)!))
    }),
    http.post(`${BASE}/tests/bulk`, async ({ request }) => {
      const body = (await request.json()) as { testIds: string[]; enabled?: boolean }
      state.bulk.push(body)
      state.tests = state.tests.map((test) =>
        body.testIds.includes(test.id) && body.enabled !== undefined ? { ...test, enabled: body.enabled } : test,
      )
      return HttpResponse.json({ updated: body.testIds.length })
    }),
    http.put(`${BASE}/tests/:testId`, async ({ request }) => {
      const body = (await request.json()) as { request: { headers: Record<string, string> } }
      state.edits.push(body)
      if ('Authorization' in body.request.headers) {
        return HttpResponse.json(
          {
            title: 'Bad Request',
            status: 400,
            detail: 'The test definition is invalid.',
            code: 'VALIDATION_ERROR',
            requestId: 'req-1',
            errors: [{ field: 'request.headers.Authorization', message: 'cannot be set by a test' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      return HttpResponse.json(detail({ ...state.tests[0]!, userModified: true }))
    }),
    http.get(`${BASE}/test-suites`, () => HttpResponse.json(state.suites)),
    http.post(`${BASE}/test-suites`, async ({ request }) => {
      const body = (await request.json()) as { name: string }
      if (state.suites.some((suite) => suite.name.toLowerCase() === body.name.toLowerCase())) {
        return problem(409, 'DUPLICATE_NAME', `A suite named '${body.name}' already exists.`)
      }
      const created: Suite = { ...SUITES[1]!, id: `suite-${state.suites.length + 1}`, name: body.name }
      state.suites = [...state.suites, created]
      return HttpResponse.json(created, { status: 201 })
    }),
    http.patch(`${BASE}/test-suites/:suiteId`, async ({ params, request }) => {
      const body = (await request.json()) as { name: string }
      state.suites = state.suites.map((suite) => (suite.id === params.suiteId ? { ...suite, name: body.name } : suite))
      return HttpResponse.json(state.suites.find((suite) => suite.id === params.suiteId))
    }),
    http.delete(`${BASE}/test-suites/:suiteId`, ({ params }) => {
      state.deletedSuites.push(String(params.suiteId))
      state.suites = state.suites.filter((suite) => suite.id !== params.suiteId)
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${BASE}/tests/:testId/review`, async ({ params, request }) => {
      const body = (await request.json()) as { decision: 'APPROVE' | 'REJECT' }
      state.reviews.push({ id: params.testId, ...body })
      state.tests = state.tests.map((test) =>
        test.id === params.testId ? { ...test, reviewStatus: body.decision === 'APPROVE' ? 'APPROVED' : 'REJECTED' } : test,
      )
      return HttpResponse.json(detail(state.tests.find((test) => test.id === params.testId)!))
    }),
  )
  return state
}

describe('Tests workspace', () => {
  it('asks for a specification before tests can be generated', async () => {
    mockSpecApi({ versions: 0 })
    server.use(
      http.get(`${BASE}/tests/summary`, () => HttpResponse.json({ total: 0, enabled: 0, stale: 0, operations: [] })),
    )
    renderApp(PAGE)

    expect(await screen.findByRole('heading', { name: 'Import a specification first' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Go to Specification' })).toHaveAttribute('href', `/projects/${PROJECT.id}/spec`)
  })

  it('generates tests and reports what was created and what was skipped, with reasons', async () => {
    const state = mockTestsApi({ generated: false })
    const user = userEvent.setup()
    renderApp(PAGE)

    expect(await screen.findByRole('heading', { name: 'No tests yet' })).toBeInTheDocument()
    await user.click(screen.getAllByRole('button', { name: 'Generate tests' })[0]!)

    expect(await screen.findByText(/Generated from version 1 \(3 endpoints, generator v1\)/)).toBeInTheDocument()
    expect(screen.getByText(/2 tests: 2 new, 0 updated, 0 unchanged/)).toBeInTheDocument()
    await user.click(screen.getByText('1 scenarios were not generated (with reasons)'))
    expect(screen.getByText(/Missing path parameter 'sku': not representable/)).toBeInTheDocument()
    expect(state.generations).toBe(1)
    const index = await screen.findByRole('navigation', { name: 'Endpoints with tests' })
    expect(within(index).getByRole('button', { name: /\/orders.*2 tests/ })).toBeInTheDocument()
  })

  it('lists the tests of an endpoint with expectations and explains what was not generated', async () => {
    mockTestsApi()
    const user = userEvent.setup()
    renderApp(`${PAGE}?operation=${encodeURIComponent('POST /orders')}`)

    const list = await screen.findByRole('list', { name: 'Tests' })
    expect(within(list).getByText("Missing required field 'customerId'")).toBeInTheDocument()
    expect(within(list).getAllByText('Missing field').length).toBeGreaterThan(0)
    expect(within(list).getByText('422')).toBeInTheDocument()
    await user.click(await screen.findByText('Not generated for this endpoint (1)'))
    expect(screen.getByText(/it is a oneOf\/anyOf composition/)).toBeInTheDocument()
  })

  it('disables one test and bulk-enables a selection', async () => {
    const state = mockTestsApi()
    const user = userEvent.setup()
    renderApp(`${PAGE}?operation=${encodeURIComponent('POST /orders')}`)

    await user.click(await screen.findByRole('switch', { name: "Enabled: Missing required field 'customerId'" }))
    await waitFor(() => expect(state.patches).toEqual([{ id: 't-1', enabled: false, version: 3 }]))

    await user.click(screen.getByRole('checkbox', { name: 'Select all tests' }))
    const toolbar = screen.getByRole('toolbar', { name: 'Selected tests' })
    await user.click(within(toolbar).getByRole('button', { name: 'Enable' }))
    await waitFor(() => expect(state.bulk).toEqual([{ testIds: ['t-1', 't-2'], enabled: true }]))
    expect(await screen.findByText('2 tests updated.')).toBeInTheDocument()
  })

  it('shows why a test exists and what it expects, and reports server validation of edits', async () => {
    const state = mockTestsApi()
    const user = userEvent.setup()
    renderApp(`${PAGE}?operation=${encodeURIComponent('POST /orders')}&test=t-1`)

    const sheet = await screen.findByRole('dialog')
    expect(await within(sheet).findByText(/is listed in 'required' of schema NewOrder/)).toBeInTheDocument()
    expect(within(sheet).getByText(/documented client-error responses/)).toBeInTheDocument()
    expect(within(sheet).getByText('#/components/schemas/NewOrder/required')).toBeInTheDocument()
    expect(within(sheet).getByText('Credentials from the environment')).toBeInTheDocument()

    await user.click(within(sheet).getByRole('button', { name: 'Edit test' }))
    const requestField = within(sheet).getByLabelText('Request (JSON)')
    await user.clear(requestField)
    await user.click(requestField)
    await user.paste('{ not json')
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    expect(within(sheet).getByText('Request: not valid JSON.')).toBeInTheDocument()
    expect(state.edits).toHaveLength(0)

    await user.clear(requestField)
    await user.paste(
      JSON.stringify({ pathParams: {}, query: {}, headers: { Authorization: 'Bearer x' }, body: { mode: 'NONE' }, auth: 'ENVIRONMENT' }),
    )
    await user.click(within(sheet).getByRole('button', { name: 'Save' }))
    expect(await within(sheet).findByText('request.headers.Authorization: cannot be set by a test')).toBeInTheDocument()
    expect(state.edits).toHaveLength(1)
  })

  it('manages suites without ever deleting tests', async () => {
    const state = mockTestsApi()
    const user = userEvent.setup()
    renderApp(`${PAGE}?operation=${encodeURIComponent('POST /orders')}`)

    await user.click(await screen.findByRole('button', { name: 'Suites' }))
    const dialog = await screen.findByRole('dialog')
    const suites = within(dialog).getByRole('list', { name: 'Suites' })
    const generated = within(suites).getByText('Generated tests').closest('li')!
    expect(within(generated).getByRole('button', { name: 'Delete' })).toBeDisabled()
    expect(within(generated).getByText(/2 tests · generated/)).toBeInTheDocument()

    await user.type(within(dialog).getByLabelText('New suite'), 'smoke')
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(await screen.findByText("A suite named 'smoke' already exists.")).toBeInTheDocument()

    await user.clear(within(dialog).getByLabelText('New suite'))
    await user.type(within(dialog).getByLabelText('New suite'), 'Regression')
    await user.click(within(dialog).getByRole('button', { name: 'Create' }))
    expect(await within(dialog).findByText('Regression')).toBeInTheDocument()

    const smoke = within(suites).getByText('Smoke').closest('li')!
    await user.click(within(smoke).getByRole('button', { name: 'Rename' }))
    const nameField = within(smoke).getByLabelText('New name for Smoke')
    await user.clear(nameField)
    await user.type(nameField, 'Release checks')
    await user.click(within(smoke).getByRole('button', { name: 'Save' }))
    expect(await within(dialog).findByText('Release checks')).toBeInTheDocument()

    const release = within(suites).getByText('Release checks').closest('li')!
    await user.click(within(release).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(state.deletedSuites).toEqual(['suite-smoke']))
    expect(state.tests).toHaveLength(2)
  })

  it('records review decisions from the test panel', async () => {
    const state = mockTestsApi()
    const user = userEvent.setup()
    renderApp(`${PAGE}?operation=${encodeURIComponent('POST /orders')}&test=t-1`)

    const sheet = await screen.findByRole('dialog')
    await user.click(await within(sheet).findByRole('button', { name: 'Reject' }))
    expect(await screen.findByText('Test rejected; it will not run.')).toBeInTheDocument()
    expect(await within(sheet).findByText('Will not run')).toBeInTheDocument()
    await user.click(within(sheet).getByRole('button', { name: 'Approve' }))
    await waitFor(() =>
      expect(state.reviews).toEqual([
        { id: 't-1', decision: 'REJECT' },
        { id: 't-1', decision: 'APPROVE' },
      ]),
    )
  })
})
