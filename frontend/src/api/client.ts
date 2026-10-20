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

/**
 * Performs a JSON request and validates the response body against `schema`, so the UI never
 * renders data whose shape it has not checked.
 */
export async function apiRequest<T>(path: string, schema: z.ZodType<T>, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json, application/problem+json' }
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
      credentials: API_BASE_URL ? 'include' : 'same-origin',
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw error
    }
    throw new ApiError({ message: 'Could not reach the APIForge backend.', status: 0, code: 'NETWORK_ERROR' })
  }

  const requestId = response.headers.get('X-Request-Id') ?? undefined
  const payload = await readJson(response)

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
