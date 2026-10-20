import { z } from 'zod'

/**
 * Base URL of the backend. Empty (the default) means same-origin: in development the Vite
 * dev server proxies /api and /actuator to the backend. Set VITE_API_BASE_URL only to call
 * the backend cross-origin; the backend must then list this origin in its CORS settings.
 */
export const API_BASE_URL: string = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

const problemSchema = z.object({
  type: z.string().optional(),
  title: z.string().optional(),
  status: z.number().optional(),
  detail: z.string().optional(),
  instance: z.string().optional(),
  code: z.string().optional(),
  requestId: z.string().optional(),
  errors: z.array(z.object({ field: z.string(), message: z.string() })).optional(),
})

export type FieldError = { field: string; message: string }

/** Error codes produced on the client side, in addition to the backend's documented codes. */
export type ClientErrorCode = 'NETWORK_ERROR' | 'INVALID_RESPONSE' | 'HTTP_ERROR'

/** A failed API call, normalised from the backend's problem+json format or a client-side failure. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string
  readonly requestId: string | undefined
  readonly fieldErrors: FieldError[]

  constructor(options: {
    message: string
    status: number
    code: string
    requestId?: string | undefined
    fieldErrors?: FieldError[]
  }) {
    super(options.message)
    this.name = 'ApiError'
    this.status = options.status
    this.code = options.code
    this.requestId = options.requestId
    this.fieldErrors = options.fieldErrors ?? []
  }
}

type RequestOptions = {
  method?: string
  body?: unknown
  signal?: AbortSignal
  /** HTTP statuses (besides 2xx) whose body should be parsed with the schema instead of treated as errors. */
  acceptStatuses?: number[]
}

/** Schema for endpoints that answer 204 No Content. */
export const noContent = z.undefined()

const CSRF_COOKIE = 'XSRF-TOKEN'
const CSRF_HEADER = 'X-XSRF-TOKEN'
const UNSAFE_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
const credentials: RequestCredentials = API_BASE_URL ? 'include' : 'same-origin'

function readCookie(name: string): string | undefined {
  const prefix = `${name}=`
  const cookie = document.cookie.split('; ').find((entry) => entry.startsWith(prefix))
  return cookie === undefined ? undefined : decodeURIComponent(cookie.slice(prefix.length))
}

/**
 * The backend issues the CSRF token as a cookie on every response (Spring Security SPA mode).
 * If none has been received yet, ask for one before the first state-changing request.
 */
async function csrfToken(forceRefresh: boolean): Promise<string | undefined> {
  if (!forceRefresh) {
    const existing = readCookie(CSRF_COOKIE)
    if (existing) {
      return existing
    }
  }
  try {
    await fetch(`${API_BASE_URL}/api/v1/auth/csrf`, { credentials })
  } catch {
    return undefined
  }
  return readCookie(CSRF_COOKIE)
}

/**
 * Performs a JSON request and validates the response body against `schema`, so the UI never
 * renders data whose shape it has not checked. State-changing requests carry the CSRF token
 * and are retried once if the token was rejected (it rotates on sign-in and sign-out).
 */
export async function apiRequest<T>(path: string, schema: z.ZodType<T>, options: RequestOptions = {}): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const unsafe = UNSAFE_METHODS.has(method)

  let response = await send(path, method, options, unsafe ? await csrfToken(false) : undefined)
  let payload = await readJson(response)
  if (unsafe && response.status === 403 && problemCode(payload) === 'CSRF_INVALID') {
    response = await send(path, method, options, await csrfToken(true))
    payload = await readJson(response)
  }

  const requestId = response.headers.get('X-Request-Id') ?? undefined

  if (!response.ok && !options.acceptStatuses?.includes(response.status)) {
    throw toApiError(response, payload, requestId)
  }

  const parsed = schema.safeParse(payload)
  if (!parsed.success) {
    throw new ApiError({
      message: 'The backend returned a response in an unexpected format.',
      status: response.status,
      code: 'INVALID_RESPONSE',
      requestId,
    })
  }
  return parsed.data
}

async function send(path: string, method: string, options: RequestOptions, csrf: string | undefined) {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (csrf) {
    headers[CSRF_HEADER] = csrf
  }
  try {
    return await fetch(`${API_BASE_URL}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      credentials,
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError({ message: 'Could not reach the APIForge backend.', status: 0, code: 'NETWORK_ERROR' })
  }
}

function problemCode(payload: unknown): string | undefined {
  const parsed = problemSchema.safeParse(payload)
  return parsed.success ? parsed.data.code : undefined
}

async function readJson(response: Response): Promise<unknown> {
  const text = await response.text()
  if (!text) {
    return undefined
  }
  try {
    return JSON.parse(text) as unknown
  } catch {
    return undefined
  }
}

function toApiError(response: Response, payload: unknown, headerRequestId: string | undefined): ApiError {
  const problem = problemSchema.safeParse(payload)
  if (problem.success && problem.data.code) {
    return new ApiError({
      message: problem.data.detail ?? problem.data.title ?? `Request failed with status ${response.status}.`,
      status: response.status,
      code: problem.data.code,
      requestId: problem.data.requestId ?? headerRequestId,
      fieldErrors: problem.data.errors,
    })
  }
  // No problem body: typically the dev proxy reporting that the backend is down (502/504).
  const unreachable = response.status === 502 || response.status === 503 || response.status === 504
  return new ApiError({
    message: unreachable
      ? 'The APIForge backend is not responding.'
      : `Request failed with status ${response.status}.`,
    status: response.status,
    code: unreachable ? 'NETWORK_ERROR' : 'HTTP_ERROR',
    requestId: headerRequestId,
  })
}
