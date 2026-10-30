import { http, HttpResponse } from 'msw'

import { PROJECT } from '@/features/specs/test-fixtures'
import { server } from '@/test/server'

import type { EnvironmentDetail } from './api'

const BASE = `/api/v1/projects/${PROJECT.id}/environments`

export const STAGING: EnvironmentDetail = {
  id: 'env-1',
  name: 'Staging',
  baseUrl: 'http://127.0.0.1:8090',
  timeoutMs: 2000,
  followRedirects: false,
  executionPolicy: 'READ_ONLY',
  auth: { type: 'API_KEY_HEADER', config: { headerName: 'X-API-Key', valueVariable: 'API_KEY' } },
  defaultHeaders: {},
  variables: [{ name: 'API_KEY', secret: true, value: null, hasValue: true, updatedAt: '2026-10-21T10:00:00Z' }],
  createdAt: '2026-10-21T10:00:00Z',
  updatedAt: '2026-10-21T10:00:00Z',
  version: 0,
}

/** In-memory environments API; returns what was written. */
export function mockEnvironmentsApi(initial: EnvironmentDetail[] = []) {
  const state = { environments: [...initial], created: [] as Record<string, unknown>[], variables: [] as unknown[] }
  server.use(
    http.get(BASE, () => HttpResponse.json(state.environments)),
    http.post(BASE, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>
      state.created.push(body)
      if (body.executionPolicy !== 'READ_ONLY' && body.authorizationConfirmed !== true) {
        return HttpResponse.json(
          {
            title: 'Bad Request',
            status: 400,
            detail: 'The environment settings are invalid.',
            code: 'VALIDATION_ERROR',
            requestId: 'r',
            errors: [{ field: 'authorizationConfirmed', message: 'confirm that the target is authorized' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      const created: EnvironmentDetail = { ...STAGING, id: `env-${state.environments.length + 1}`, name: String(body.name), baseUrl: String(body.baseUrl), executionPolicy: body.executionPolicy as EnvironmentDetail['executionPolicy'], variables: [] }
      state.environments.push(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.put(`${BASE}/:id/variables/:name`, async ({ params, request }) => {
      const body = (await request.json()) as { value: string; secret: boolean }
      state.variables.push({ name: params.name, ...body })
      state.environments = state.environments.map((environment) =>
        environment.id === params.id
          ? { ...environment, variables: [...environment.variables, { name: String(params.name), secret: body.secret, value: body.secret ? null : body.value, hasValue: true, updatedAt: '2026-10-21T10:00:00Z' }] }
          : environment,
      )
      return HttpResponse.json({ name: params.name, secret: body.secret, value: body.secret ? null : body.value, hasValue: true, updatedAt: '2026-10-21T10:00:00Z' })
    }),
  )
  return state
}
