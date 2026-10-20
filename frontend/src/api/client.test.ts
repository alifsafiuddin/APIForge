import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { z } from 'zod'

import { server } from '@/test/server'

import { ApiError, apiRequest } from './client'

const itemSchema = z.object({ id: z.number(), name: z.string() })

async function captureError(promise: Promise<unknown>): Promise<ApiError> {
  try {
    await promise
  } catch (error) {
    if (error instanceof ApiError) {
      return error
    }
    throw error
  }
  throw new Error('Expected the request to fail')
}

describe('apiRequest', () => {
  it('returns schema-validated data on success', async () => {
    server.use(http.get('/api/v1/items/1', () => HttpResponse.json({ id: 1, name: 'pet' })))

    await expect(apiRequest('/api/v1/items/1', itemSchema)).resolves.toEqual({ id: 1, name: 'pet' })
  })

  it('maps problem+json responses to ApiError with code, request id and field errors', async () => {
    server.use(
      http.post('/api/v1/items', () =>
        HttpResponse.json(
          {
            title: 'Bad Request',
            status: 400,
            detail: 'One or more fields are invalid.',
            code: 'VALIDATION_ERROR',
            requestId: 'req-12345678',
            errors: [{ field: 'name', message: 'must not be blank' }],
          },
          { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )

    const error = await captureError(apiRequest('/api/v1/items', itemSchema, { method: 'POST', body: {} }))

    expect(error.status).toBe(400)
    expect(error.code).toBe('VALIDATION_ERROR')
    expect(error.message).toBe('One or more fields are invalid.')
    expect(error.requestId).toBe('req-12345678')
    expect(error.fieldErrors).toEqual([{ field: 'name', message: 'must not be blank' }])
  })

  it('rejects responses that do not match the expected schema', async () => {
    server.use(http.get('/api/v1/items/1', () => HttpResponse.json({ id: 'not-a-number' })))

    const error = await captureError(apiRequest('/api/v1/items/1', itemSchema))

    expect(error.code).toBe('INVALID_RESPONSE')
  })

  it('reports network failures as NETWORK_ERROR', async () => {
    server.use(http.get('/api/v1/items/1', () => HttpResponse.error()))

    const error = await captureError(apiRequest('/api/v1/items/1', itemSchema))

    expect(error.code).toBe('NETWORK_ERROR')
    expect(error.status).toBe(0)
  })

  it('treats a bodiless gateway error from the dev proxy as the backend being unreachable', async () => {
    server.use(http.get('/api/v1/items/1', () => new HttpResponse(null, { status: 502 })))

    const error = await captureError(apiRequest('/api/v1/items/1', itemSchema))

    expect(error.code).toBe('NETWORK_ERROR')
    expect(error.status).toBe(502)
  })

  it('parses the body of explicitly accepted non-2xx statuses', async () => {
    const statusSchema = z.object({ status: z.string() })
    server.use(http.get('/actuator/health', () => HttpResponse.json({ status: 'DOWN' }, { status: 503 })))

    await expect(apiRequest('/actuator/health', statusSchema, { acceptStatuses: [503] })).resolves.toEqual({
      status: 'DOWN',
    })
  })
})
