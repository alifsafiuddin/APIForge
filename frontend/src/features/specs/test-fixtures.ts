import { http, HttpResponse } from 'msw'

import { problem, server } from '@/test/server'

import type { ImportMessage, OperationDetail, OperationSummary, VersionSummary } from './api'

export const PROJECT = {
  id: 'proj-1',
  name: 'Inventory',
  description: 'Warehouse API',
  archived: false,
  archivedAt: null,
  activeSpecificationVersionId: null as string | null,
  createdAt: '2026-10-21T10:00:00Z',
  updatedAt: '2026-10-21T10:00:00Z',
  version: 0,
}

const OPERATIONS: OperationDetail[] = [
  {
    id: 'op-1',
    specificationVersionId: 'v-1',
    operationKey: 'GET /orders',
    method: 'GET',
    path: '/orders',
    operationId: 'listOrders',
    summary: 'List orders',
    description: 'Returns orders, newest first.',
    tags: ['orders'],
    deprecated: false,
    parameters: [
      { name: 'pageSize', in: 'query', required: false, description: 'Items per page', schema: { type: 'integer', maximum: 100 } },
      { name: 'X-Trace', in: 'header', required: true, schema: { type: 'string' } },
    ],
    requestBody: null,
    responses: [
      {
        status: '200',
        description: 'Orders',
        headers: { 'X-Total-Count': { schema: { type: 'integer' } } },
        content: { 'application/json': { schema: { type: 'array', items: { $ref: '#/components/schemas/Order' } } } },
      },
      { status: '401', description: 'Authentication required' },
    ],
    security: [{ bearerAuth: [] }],
    warnings: [],
  },
  {
    id: 'op-2',
    specificationVersionId: 'v-1',
    operationKey: 'POST /orders',
    method: 'POST',
    path: '/orders',
    operationId: 'createOrder',
    summary: 'Create order',
    description: null,
    tags: ['orders'],
    deprecated: false,
    parameters: [],
    requestBody: { required: true, content: { 'application/json': { schema: { $ref: '#/components/schemas/Order' } } } },
    responses: [{ status: '201', description: 'Created' }],
    security: [],
    warnings: [{ severity: 'WARNING', code: 'UNSUPPORTED_FEATURE', message: 'Callbacks are shown for reference only and are not tested.', location: 'paths["/orders"].post' }],
  },
  {
    id: 'op-3',
    specificationVersionId: 'v-1',
    operationKey: 'DELETE /products/{sku}',
    method: 'DELETE',
    path: '/products/{sku}',
    operationId: 'deleteProduct',
    summary: 'Delete product',
    description: null,
    tags: ['products'],
    deprecated: true,
    parameters: [{ name: 'sku', in: 'path', required: true, schema: { type: 'string' } }],
    requestBody: null,
    responses: [{ status: '204', description: 'Deleted' }],
    security: [],
    warnings: [],
  },
]

const SCHEMAS = {
  Order: {
    type: 'object',
    required: ['id'],
    properties: { id: { type: 'string', format: 'uuid' }, status: { type: 'string', enum: ['PENDING', 'SHIPPED'] } },
  },
}

function summary(versionNumber: number, active: boolean, id = `v-${versionNumber}`): VersionSummary {
  return {
    id,
    versionNumber,
    title: 'Inventory API',
    apiVersion: `${versionNumber}.0.0`,
    openapiVersion: '3.0.3',
    sourceFormat: 'YAML',
    sourceName: 'inventory.yaml',
    operationCount: OPERATIONS.length,
    warningCount: 1,
    active,
    contentSha256: 'a'.repeat(64),
    contentSizeBytes: 4096,
    importedAt: '2026-10-21T10:00:00Z',
  }
}

/** A first, active version as the import endpoint would return it. */
export const SAMPLE_VERSION = summary(1, true)

const OPERATION_SUMMARIES: OperationSummary[] = OPERATIONS.map(({ id, operationKey, method, path, operationId, summary, tags, deprecated, warnings }) => ({
  id,
  operationKey,
  method,
  path,
  operationId,
  summary,
  tags,
  deprecated,
  warningCount: warnings.length,
}))

const VERSION_MESSAGES: ImportMessage[] = [
  { severity: 'WARNING', code: 'UNSUPPORTED_FEATURE', message: "Security scheme 'oauth' (OAUTH2) is not performed automatically.", location: 'components.securitySchemes.oauth' },
]

/** In-memory specs API for one project. Returns state the tests can inspect. */
export function mockSpecApi({ versions: initial = 0 }: { versions?: number } = {}) {
  const state = {
    versions: Array.from({ length: initial }, (_, index) => summary(initial - index, index === 0)),
    imports: [] as { body: unknown; activate: string | null }[],
    activations: [] as string[],
  }
  const base = `/api/v1/projects/${PROJECT.id}`

  server.use(
    http.get(base, () =>
      HttpResponse.json({ ...PROJECT, activeSpecificationVersionId: state.versions.find((v) => v.active)?.id ?? null }),
    ),
    http.get(`${base}/specs`, () =>
      HttpResponse.json({
        content: state.versions,
        page: { number: 0, size: 100, totalElements: state.versions.length, totalPages: state.versions.length ? 1 : 0 },
      }),
    ),
    http.get(`${base}/specs/:versionId`, ({ params }) => {
      const version = state.versions.find((v) => v.id === params.versionId)
      return version
        ? HttpResponse.json({
            summary: version,
            description: 'Warehouse inventory.',
            servers: [{ url: 'https://inventory.example.test/api', description: 'Production' }],
            securitySchemes: { bearerAuth: { type: 'http', scheme: 'bearer' } },
            tags: [{ name: 'orders', description: 'Customer orders' }],
            messages: VERSION_MESSAGES,
          })
        : problem(404, 'NOT_FOUND', 'Specification version not found.')
    }),
    http.get(`${base}/specs/:versionId/operations`, () => HttpResponse.json(OPERATION_SUMMARIES)),
    http.get(`${base}/specs/:versionId/operations/:operationId`, ({ params }) => {
      const operation = OPERATIONS.find((op) => op.id === params.operationId)
      return operation ? HttpResponse.json(operation) : problem(404, 'NOT_FOUND', 'Operation not found.')
    }),
    http.get(`${base}/specs/:versionId/schemas`, () => HttpResponse.json(SCHEMAS)),
    http.post(`${base}/specs/:versionId/activate`, ({ params }) => {
      state.activations.push(String(params.versionId))
      state.versions = state.versions.map((v) => ({ ...v, active: v.id === params.versionId }))
      return HttpResponse.json(state.versions.find((v) => v.id === params.versionId))
    }),
    http.post(`${base}/specs/validate`, async ({ request }) => {
      const content = await bodyText(request)
      return HttpResponse.json(
        content.includes('openapi')
          ? { valid: true, openapiVersion: '3.0.3', title: 'Inventory API', apiVersion: '1.0.0', operationCount: 3, messages: [] }
          : { valid: false, openapiVersion: null, title: null, apiVersion: null, operationCount: null, messages: [{ severity: 'ERROR', code: 'NOT_AN_OBJECT', message: 'The document must be a JSON or YAML object.' }] },
      )
    }),
    http.post(`${base}/specs`, async ({ request }) => {
      const content = await bodyText(request)
      state.imports.push({ body: content, activate: new URL(request.url).searchParams.get('activate') })
      if (!content.includes('info')) {
        return HttpResponse.json(
          {
            title: 'Unprocessable Content',
            status: 422,
            detail: 'The specification cannot be imported: 1 problem found.',
            code: 'SPEC_INVALID',
            requestId: 'req-test-0002',
            messages: [{ severity: 'ERROR', code: 'MISSING_FIELD', message: "Required field 'info' is missing.", location: '(document root)' }],
          },
          { status: 422, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      const activate = new URL(request.url).searchParams.get('activate') !== 'false' || state.versions.length === 0
      const created = summary(state.versions.length + 1, activate)
      state.versions = [created, ...state.versions.map((v) => (activate ? { ...v, active: false } : v))]
      return HttpResponse.json({ version: created, messages: VERSION_MESSAGES }, { status: 201 })
    }),
  )
  return state
}

async function bodyText(request: Request): Promise<string> {
  const json = (await request.json()) as { content?: string }
  return json.content ?? ''
}
