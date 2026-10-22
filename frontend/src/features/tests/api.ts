import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest, noContent } from '@/api/client'
import { pageSchema } from '@/api/page'

export const CATEGORIES = [
  'HAPPY_PATH',
  'AUTH',
  'MISSING_FIELD',
  'INVALID_TYPE',
  'ENUM_VALUE',
  'BOUNDARY_VALUE',
  'STRING_LENGTH',
  'ARRAY_LENGTH',
  'QUERY_PARAMETER',
  'PATH_PARAMETER',
  'HEADER',
  'CONTENT_TYPE',
  'RESPONSE_VALIDATION',
  'CUSTOM',
] as const
export type Category = (typeof CATEGORIES)[number]

export const CATEGORY_LABELS: Record<Category, string> = {
  HAPPY_PATH: 'Valid request',
  AUTH: 'Authentication',
  MISSING_FIELD: 'Missing field',
  INVALID_TYPE: 'Wrong type',
  ENUM_VALUE: 'Enum value',
  BOUNDARY_VALUE: 'Boundary value',
  STRING_LENGTH: 'String length',
  ARRAY_LENGTH: 'Array length',
  QUERY_PARAMETER: 'Query parameter',
  PATH_PARAMETER: 'Path parameter',
  HEADER: 'Header',
  CONTENT_TYPE: 'Content type',
  RESPONSE_VALIDATION: 'Response validation',
  CUSTOM: 'Custom',
}

const categorySchema = z.enum(CATEGORIES)
const originSchema = z.enum(['DETERMINISTIC', 'AI_SUGGESTED', 'MANUAL'])
const reviewStatusSchema = z.enum(['APPROVED', 'PENDING_REVIEW', 'REJECTED'])
const lifecycleSchema = z.enum(['ACTIVE', 'STALE', 'ARCHIVED'])
const riskSchema = z.enum(['READ_ONLY', 'MUTATING', 'DESTRUCTIVE'])

export const testSummarySchema = z.object({
  id: z.string(),
  suiteId: z.string(),
  operationKey: z.string(),
  method: z.string(),
  path: z.string(),
  name: z.string(),
  category: categorySchema,
  origin: originSchema,
  reviewStatus: reviewStatusSchema,
  lifecycleStatus: lifecycleSchema,
  riskLevel: riskSchema,
  enabled: z.boolean(),
  userModified: z.boolean(),
  expectedStatuses: z.array(z.string()),
  sortOrder: z.number(),
  updatedAt: z.string(),
  version: z.number(),
})
export type TestSummary = z.infer<typeof testSummarySchema>

const bodySchema = z.object({
  mode: z.enum(['NONE', 'JSON', 'RAW']),
  contentType: z.string().nullable().optional(),
  value: z.unknown().optional(),
})

export const requestSchema = z.object({
  pathParams: z.record(z.string(), z.unknown()),
  query: z.record(z.string(), z.unknown()),
  headers: z.record(z.string(), z.string()),
  body: bodySchema,
  auth: z.enum(['ENVIRONMENT', 'NONE', 'INVALID']),
})
export type TestRequest = z.infer<typeof requestSchema>

const expectationsSchema = z.object({
  statuses: z.array(z.string()),
  summary: z.string(),
  validateResponseSchema: z.boolean(),
  responseChecks: z.array(
    z.object({ status: z.string(), mediaType: z.string(), schemaName: z.string().nullable().optional() }),
  ),
})
export type TestExpectations = z.infer<typeof expectationsSchema>

export const testDetailSchema = testSummarySchema.omit({ expectedStatuses: true, sortOrder: true }).extend({
  executable: z.boolean(),
  scenarioKey: z.string().nullable(),
  specLocation: z.string().nullable(),
  rationale: z.string().nullable(),
  generatorVersion: z.string().nullable(),
  aiModel: z.string().nullable().optional(),
  sourceSpecificationVersionId: z.string().nullable(),
  request: requestSchema,
  expectations: expectationsSchema,
  reviewedBy: z.string().nullable(),
  reviewedAt: z.string().nullable(),
  createdAt: z.string(),
  version: z.number(),
})
export type TestDetail = z.infer<typeof testDetailSchema>

const operationCountSchema = z.object({
  operationKey: z.string(),
  total: z.number(),
  enabled: z.number(),
  stale: z.number(),
})
export const testCountsSchema = z.object({
  total: z.number(),
  enabled: z.number(),
  stale: z.number(),
  operations: z.array(operationCountSchema),
})
export type TestCounts = z.infer<typeof testCountsSchema>

const skippedSchema = z.object({ operationKey: z.string(), scenario: z.string(), reason: z.string() })
export type SkippedScenario = z.infer<typeof skippedSchema>

export const generationReportSchema = z.object({
  specificationVersionId: z.string(),
  specificationVersionNumber: z.number(),
  operations: z.number(),
  created: z.number(),
  updated: z.number(),
  unchanged: z.number(),
  keptUserEdits: z.number(),
  archived: z.number(),
  total: z.number(),
  generatorVersion: z.string(),
  skipped: z.array(skippedSchema),
})
export type GenerationReport = z.infer<typeof generationReportSchema>

const generationPlanSchema = z.object({
  operationKey: z.string(),
  specificationVersionId: z.string(),
  generatorVersion: z.string(),
  scenarios: z.array(
    z.object({ scenarioKey: z.string(), name: z.string(), category: categorySchema, expectedStatuses: z.array(z.string()) }),
  ),
  skipped: z.array(skippedSchema),
})

export const suiteSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  kind: z.enum(['GENERATED', 'AI_SUGGESTIONS', 'CUSTOM']),
  testCount: z.number(),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number(),
})
export type Suite = z.infer<typeof suiteSchema>

export type TestFilter = {
  operationKey?: string | undefined
  suiteId?: string | undefined
  category?: Category | undefined
  enabled?: boolean | undefined
  origin?: z.infer<typeof originSchema> | undefined
  reviewStatus?: z.infer<typeof reviewStatusSchema> | undefined
  lifecycleStatus?: 'ACTIVE' | 'STALE' | undefined
  /** Case-insensitive match on the test name. */
  q?: string | undefined
  page?: number | undefined
  size?: number | undefined
}

export const testKeys = {
  all: (projectId: string) => ['tests', projectId] as const,
  counts: (projectId: string) => ['tests', projectId, 'counts'] as const,
  list: (projectId: string, filter: TestFilter) => ['tests', projectId, 'list', filter] as const,
  detail: (projectId: string, testId: string) => ['tests', projectId, 'detail', testId] as const,
  plan: (projectId: string, operationKey: string) => ['tests', projectId, 'plan', operationKey] as const,
  suites: (projectId: string) => ['tests', projectId, 'suites'] as const,
}

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}`

export function useTestCounts(projectId: string) {
  return useQuery({
    queryKey: testKeys.counts(projectId),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/tests/summary`, testCountsSchema, { signal }),
  })
}

/**
 * One page (default: up to 100 tests, the page limit) matching the filter, in generation order.
 * With `keepPrevious`, the previous page stays visible (marked as placeholder) while the next loads.
 */
export function useTests(projectId: string, filter: TestFilter, enabled = true, keepPrevious = false) {
  const params = new URLSearchParams({ size: String(filter.size ?? 100), page: String(filter.page ?? 0) })
  if (filter.operationKey) params.set('operationKey', filter.operationKey)
  if (filter.suiteId) params.set('suiteId', filter.suiteId)
  if (filter.category) params.set('category', filter.category)
  if (filter.enabled !== undefined) params.set('enabled', String(filter.enabled))
  if (filter.origin) params.set('origin', filter.origin)
  if (filter.reviewStatus) params.set('reviewStatus', filter.reviewStatus)
  if (filter.lifecycleStatus) params.set('lifecycleStatus', filter.lifecycleStatus)
  if (filter.q) params.set('q', filter.q)
  return useQuery({
    queryKey: testKeys.list(projectId, filter),
    queryFn: ({ signal }) =>
      apiRequest(`${base(projectId)}/tests?${params.toString()}`, pageSchema(testSummarySchema), { signal }),
    enabled,
    placeholderData: keepPrevious ? keepPreviousData : undefined,
  })
}

export function useTest(projectId: string, testId: string | undefined) {
  return useQuery({
    queryKey: testKeys.detail(projectId, testId ?? ''),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/tests/${testId}`, testDetailSchema, { signal }),
    enabled: testId !== undefined,
  })
}

export function useGenerationPlan(projectId: string, operationKey: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: testKeys.plan(projectId, operationKey ?? ''),
    queryFn: ({ signal }) =>
      apiRequest(
        `${base(projectId)}/tests/generation-plan?operationKey=${encodeURIComponent(operationKey ?? '')}`,
        generationPlanSchema,
        { signal },
      ),
    enabled: enabled && operationKey !== undefined,
  })
}

export function useSuites(projectId: string) {
  return useQuery({
    queryKey: testKeys.suites(projectId),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/test-suites`, z.array(suiteSchema), { signal }),
  })
}

function useInvalidateTests(projectId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: testKeys.all(projectId) })
}

export function useGenerateTests(projectId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: (operationKeys?: string[]) =>
      apiRequest(`${base(projectId)}/tests/generate`, generationReportSchema, {
        method: 'POST',
        body: operationKeys ? { operationKeys } : {},
      }),
    onSuccess: invalidate,
  })
}

export type TestEdit = {
  name: string
  request: TestRequest
  expectations: { statuses: string[]; summary: string; validateResponseSchema: boolean }
  rationale: string | null
  version: number
}

export function useEditTest(projectId: string, testId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: (edit: TestEdit) =>
      apiRequest(`${base(projectId)}/tests/${testId}`, testDetailSchema, { method: 'PUT', body: edit }),
    onSuccess: invalidate,
  })
}

export function useChangeTest(projectId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: ({ testId, ...change }: { testId: string; enabled?: boolean; suiteId?: string; version: number }) =>
      apiRequest(`${base(projectId)}/tests/${testId}`, testDetailSchema, { method: 'PATCH', body: change }),
    onSuccess: invalidate,
  })
}

export function useBulkChange(projectId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: (change: { testIds: string[]; enabled?: boolean; suiteId?: string }) =>
      apiRequest(`${base(projectId)}/tests/bulk`, z.object({ updated: z.number() }), { method: 'POST', body: change }),
    onSuccess: invalidate,
  })
}

export function useReviewTest(projectId: string, testId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: (decision: 'APPROVE' | 'REJECT') =>
      apiRequest(`${base(projectId)}/tests/${testId}/review`, testDetailSchema, {
        method: 'POST',
        body: { decision },
      }),
    onSuccess: invalidate,
  })
}

export function useCreateSuite(projectId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: (suite: { name: string; description?: string }) =>
      apiRequest(`${base(projectId)}/test-suites`, suiteSchema, { method: 'POST', body: suite }),
    onSuccess: invalidate,
  })
}

export function useRenameSuite(projectId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: ({ suiteId, ...change }: { suiteId: string; name: string; version: number }) =>
      apiRequest(`${base(projectId)}/test-suites/${suiteId}`, suiteSchema, { method: 'PATCH', body: change }),
    onSuccess: invalidate,
  })
}

export function useDeleteSuite(projectId: string) {
  const invalidate = useInvalidateTests(projectId)
  return useMutation({
    mutationFn: (suiteId: string) =>
      apiRequest(`${base(projectId)}/test-suites/${suiteId}`, noContent, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}
