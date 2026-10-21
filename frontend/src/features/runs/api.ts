import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest } from '@/api/client'
import { pageSchema } from '@/api/page'
import { testKeys } from '@/features/tests/api'

export const OUTCOMES = ['PASSED', 'FAILED', 'INCONCLUSIVE', 'ERROR', 'SKIPPED'] as const
export type Outcome = (typeof OUTCOMES)[number]
const RUN_STATUSES = ['QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'INTERRUPTED'] as const
export type RunStatus = (typeof RUN_STATUSES)[number]

export const runSchema = z.object({
  id: z.string(),
  projectId: z.string(),
  kind: z.enum(['TEST_RUN', 'AD_HOC']),
  status: z.enum(RUN_STATUSES),
  statusReason: z.string().nullable(),
  environmentId: z.string().nullable(),
  environmentName: z.string().nullable(),
  baseUrl: z.string().nullable(),
  suiteId: z.string().nullable(),
  specificationVersionId: z.string().nullable(),
  triggeredBy: z.string().nullable(),
  counts: z.object({
    total: z.number(),
    passed: z.number(),
    failed: z.number(),
    inconclusive: z.number(),
    error: z.number(),
    skipped: z.number(),
  }),
  createdAt: z.string(),
  startedAt: z.string().nullable(),
  finishedAt: z.string().nullable(),
})
export type Run = z.infer<typeof runSchema>

export const resultSummarySchema = z.object({
  id: z.string(),
  testCaseId: z.string().nullable(),
  testName: z.string().nullable(),
  operationKey: z.string(),
  outcome: z.enum(OUTCOMES),
  responseStatus: z.number().nullable(),
  durationMs: z.number().nullable(),
  errorKind: z.string().nullable(),
  errorMessage: z.string().nullable(),
  executedAt: z.string(),
})
export type ResultSummary = z.infer<typeof resultSummarySchema>

const checkSchema = z.object({ name: z.string(), result: z.string(), message: z.string() })
export type Check = z.infer<typeof checkSchema>

export const resultDetailSchema = z.object({
  id: z.string(),
  testRunId: z.string(),
  testCaseId: z.string().nullable(),
  testName: z.string().nullable(),
  operationKey: z.string(),
  outcome: z.enum(OUTCOMES),
  request: z.record(z.string(), z.unknown()),
  response: z
    .object({
      status: z.number(),
      headers: z.record(z.string(), z.unknown()).nullable(),
      body: z.string().nullable(),
      sizeBytes: z.number().nullable(),
      truncated: z.boolean(),
      contentType: z.string().nullable(),
    })
    .nullable(),
  checks: z.array(checkSchema),
  errorKind: z.string().nullable(),
  errorMessage: z.string().nullable(),
  durationMs: z.number().nullable(),
  correlationId: z.string().nullable(),
  testCase: z.record(z.string(), z.unknown()).nullable(),
  executedAt: z.string(),
})
export type ResultDetail = z.infer<typeof resultDetailSchema>

export const runKeys = {
  all: (projectId: string) => ['runs', projectId] as const,
  list: (projectId: string, page: number) => ['runs', projectId, 'list', page] as const,
  run: (projectId: string, runId: string) => ['runs', projectId, 'run', runId] as const,
  results: (projectId: string, runId: string) => ['runs', projectId, 'results', runId] as const,
  result: (projectId: string, runId: string, resultId: string) => ['runs', projectId, 'result', runId, resultId] as const,
}

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}`
export const isActive = (status: RunStatus) => status === 'QUEUED' || status === 'RUNNING'

export function useRuns(projectId: string, page: number) {
  return useQuery({
    queryKey: runKeys.list(projectId, page),
    queryFn: ({ signal }) =>
      apiRequest(`${base(projectId)}/runs?size=20&page=${page}&sort=createdAt,desc`, pageSchema(runSchema), { signal }),
    refetchInterval: (query) => (query.state.data?.content.some((run) => isActive(run.status)) ? 2000 : false),
  })
}

/** Polls every second while the run is queued or running. */
export function useRun(projectId: string, runId: string) {
  return useQuery({
    queryKey: runKeys.run(projectId, runId),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/runs/${runId}`, runSchema, { signal }),
    refetchInterval: (query) => (query.state.data && isActive(query.state.data.status) ? 1000 : false),
  })
}

export function useRunResults(projectId: string, runId: string, active: boolean) {
  return useQuery({
    queryKey: runKeys.results(projectId, runId),
    queryFn: ({ signal }) =>
      apiRequest(`${base(projectId)}/runs/${runId}/results?size=100`, pageSchema(resultSummarySchema), { signal }),
    refetchInterval: active ? 1000 : false,
  })
}

export function useResult(projectId: string, runId: string, resultId: string | undefined) {
  return useQuery({
    queryKey: runKeys.result(projectId, runId, resultId ?? ''),
    queryFn: ({ signal }) =>
      apiRequest(`${base(projectId)}/runs/${runId}/results/${resultId}`, resultDetailSchema, { signal }),
    enabled: resultId !== undefined,
  })
}

export function useStartRun(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: { environmentId: string; testIds?: string[]; operationKey?: string }) =>
      apiRequest(`${base(projectId)}/runs`, runSchema, { method: 'POST', body }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: runKeys.all(projectId) }),
  })
}

export function useCancelRun(projectId: string, runId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiRequest(`${base(projectId)}/runs/${runId}/cancel`, runSchema, { method: 'POST' }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: runKeys.all(projectId) }),
  })
}

export type AdHocInput = {
  environmentId: string
  method: string
  path: string
  request: {
    pathParams: Record<string, unknown>
    query: Record<string, unknown>
    headers: Record<string, string>
    body: { mode: 'NONE' | 'JSON' | 'RAW'; contentType?: string; value?: unknown }
    auth: 'ENVIRONMENT' | 'NONE' | 'INVALID'
  }
  expectedStatuses?: string[]
}

export function useAdHocRequest(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: AdHocInput) =>
      apiRequest(`${base(projectId)}/executions`, resultDetailSchema, { method: 'POST', body: input }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: runKeys.all(projectId) })
      await queryClient.invalidateQueries({ queryKey: testKeys.all(projectId) })
    },
  })
}
