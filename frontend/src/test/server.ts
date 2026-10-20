import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

/** Default healthy-backend responses; individual tests override them with server.use(...). */
export const defaultHandlers = [
  http.get('/actuator/health', () =>
    HttpResponse.json({
      status: 'UP',
      components: { db: { status: 'UP' }, diskSpace: { status: 'UP' }, ping: { status: 'UP' } },
    }),
  ),
  http.get('/api/v1/system/info', () =>
    HttpResponse.json({
      name: 'APIForge AI',
      version: '0.1.0-test',
      apiVersion: 'v1',
      serverTime: '2026-10-20T12:00:05Z',
    }),
  ),
]

export const server = setupServer(...defaultHandlers)
