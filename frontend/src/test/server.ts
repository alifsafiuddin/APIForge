import { http, HttpResponse } from 'msw'
import { setupServer } from 'msw/node'

export const TEST_USER = { id: 'user-1', email: 'ada@example.test', displayName: 'Ada Lovelace' }
export const CSRF_TOKEN = 'test-csrf-token'

export function emptyPage(size = 10) {
  return { content: [], page: { number: 0, size, totalElements: 0, totalPages: 0 } }
}

/** Mimics the browser storing the cookie that the backend sets on every response. */
export function issueCsrfCookie() {
  document.cookie = `XSRF-TOKEN=${CSRF_TOKEN}; path=/`
}

export function clearCookies() {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
}

export function problem(status: number, code: string, detail: string, errors?: { field: string; message: string }[]) {
  return HttpResponse.json(
    { title: 'Error', status, detail, code, requestId: 'req-test-0001', ...(errors ? { errors } : {}) },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Local AI with the model installed (the header chip asks on every page). */
export const AI_READY = {
  enabled: true,
  state: 'READY',
  model: 'qwen2.5:7b',
  server: 'http://localhost:11434',
  installedModels: ['qwen2.5:7b'],
  message: 'Ollama is reachable and the model is installed.',
  latencyMs: 3,
  checkedAt: '2026-10-22T10:00:00Z',
}

export const EMPTY_DASHBOARD = {
  totals: {
    activeProjects: 0,
    archivedProjects: 0,
    runsLast7Days: 0,
    runsWithFailuresLast7Days: 0,
    runsInProgress: 0,
    testsPendingReview: 0,
    staleTests: 0,
  },
  projects: [],
  recentRuns: [],
}

/** Default: a signed-in user with no projects and a healthy backend. Tests override with server.use(...). */
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
  http.get('/api/v1/auth/csrf', () => {
    issueCsrfCookie()
    return new HttpResponse(null, { status: 204 })
  }),
  http.get('/api/v1/auth/me', () => HttpResponse.json(TEST_USER)),
  http.get('/api/v1/ai/status', () => HttpResponse.json(AI_READY)),
  http.get('/api/v1/dashboard', () => HttpResponse.json(EMPTY_DASHBOARD)),
  http.get('/api/v1/projects', ({ request }) => {
    const size = Number(new URL(request.url).searchParams.get('size') ?? 10)
    return HttpResponse.json(emptyPage(size))
  }),
]

export const server = setupServer(...defaultHandlers)
