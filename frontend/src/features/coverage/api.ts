import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest } from '@/api/client'
import { OUTCOMES } from '@/features/runs/api'

export const NEGATIVE_KINDS = [
  'AUTHENTICATION',
  'MISSING_REQUIRED',
  'WRONG_TYPE',
  'OUT_OF_RANGE',
  'INVALID_VALUE',
  'UNSUPPORTED_CONTENT_TYPE',
] as const
const KINDS = ['VALID_REQUEST', 'ACCEPTED_BOUNDARY', ...NEGATIVE_KINDS, 'OTHER'] as const
export type ScenarioKind = (typeof KINDS)[number]

export const KIND_LABELS: Record<ScenarioKind, string> = {
  VALID_REQUEST: 'Valid request',
  ACCEPTED_BOUNDARY: 'Accepted boundary value',
  AUTHENTICATION: 'Missing or invalid credentials',
  MISSING_REQUIRED: 'Missing required input',
  WRONG_TYPE: 'Wrong type',
  OUT_OF_RANGE: 'Out-of-range value',
  INVALID_VALUE: 'Value outside the allowed set',
  UNSUPPORTED_CONTENT_TYPE: 'Unsupported content type',
  OTHER: 'Other',
}

const versionSchema = z.object({
  id: z.string(),
  number: z.number(),
  title: z.string().nullable(),
  apiVersion: z.string().nullable(),
  importedAt: z.string(),
})
export type VersionRef = z.infer<typeof versionSchema>

const outcomeCountsSchema = z.object({
  tests: z.number(),
  executed: z.number(),
  passed: z.number(),
  failed: z.number(),
  error: z.number(),
  inconclusive: z.number(),
  skipped: z.number(),
  notRun: z.number(),
})
export type OutcomeCounts = z.infer<typeof outcomeCountsSchema>

const metricSchema = z.object({
  id: z.string(),
  group: z.enum(['TEST_DESIGN', 'EXECUTION']),
  label: z.string(),
  value: z.number(),
  total: z.number().nullable(),
  definition: z.string(),
})
export type Metric = z.infer<typeof metricSchema>

const rowSchema = z.object({
  operationKey: z.string(),
  method: z.string(),
  path: z.string(),
  tags: z.array(z.string()),
  deprecated: z.boolean(),
  tests: z.number(),
  duplicates: z.number(),
  inactiveTests: z.number(),
  responses: z.number(),
  responsesTargeted: z.number(),
  responsesExercised: z.number(),
  constraints: z.number(),
  constraintsCovered: z.number(),
  branches: z.number(),
  branchesExercised: z.number(),
  missingNegatives: z.array(z.enum(KINDS)),
  undocumentedStatuses: z.array(z.string()),
  outcomes: outcomeCountsSchema,
})
export type OperationRow = z.infer<typeof rowSchema>

export const coverageSchema = z.object({
  version: versionSchema,
  environmentId: z.string().nullable(),
  metrics: z.array(metricSchema),
  outcomes: outcomeCountsSchema,
  operations: z.array(rowSchema),
  gaps: z.object({
    operationsWithoutTests: z.array(z.string()),
    responsesNotExercised: z.array(z.object({ operationKey: z.string(), status: z.string(), targeted: z.boolean() })),
    missingNegatives: z.array(z.object({ kind: z.enum(KINDS), label: z.string(), operations: z.array(z.string()) })),
  }),
})
export type CoverageReport = z.infer<typeof coverageSchema>

export const operationCoverageSchema = z.object({
  version: versionSchema,
  summary: rowSchema,
  tests: z.array(
    z.object({
      id: z.string(),
      name: z.string(),
      scenarioKey: z.string().nullable(),
      kind: z.enum(KINDS).nullable(),
      duplicateOf: z.string().nullable(),
      latestOutcome: z.enum(OUTCOMES).nullable(),
      latestStatus: z.number().nullable(),
      lastExecutedAt: z.string().nullable(),
    }),
  ),
  scenarios: z.array(
    z.object({ scenarioKey: z.string(), name: z.string(), kind: z.enum(KINDS), covered: z.boolean(), testIds: z.array(z.string()) }),
  ),
  skipped: z.array(z.object({ scenario: z.string(), reason: z.string() })),
  responses: z.array(
    z.object({ status: z.string(), targeted: z.boolean(), exercised: z.boolean(), receivedStatuses: z.array(z.number()) }),
  ),
  branches: z.array(z.object({ id: z.string(), path: z.string(), label: z.string(), exercised: z.boolean() })),
  negatives: z.array(z.object({ kind: z.enum(KINDS), label: z.string(), state: z.enum(['PRESENT', 'MISSING', 'NOT_APPLICABLE']) })),
})
export type OperationCoverage = z.infer<typeof operationCoverageSchema>

const snapshotSchema = z.object({
  id: z.string(),
  specificationVersionId: z.string(),
  versionNumber: z.number(),
  trigger: z.enum(['TESTS_GENERATED', 'RUN_FINISHED', 'VERSION_ACTIVATED']),
  runId: z.string().nullable(),
  takenAt: z.string(),
  metrics: z.record(z.string(), z.number()),
})
export type Snapshot = z.infer<typeof snapshotSchema>

export const changesSchema = z.object({
  from: versionSchema.nullable(),
  to: versionSchema,
  operations: z.array(
    z.object({
      operationKey: z.string(),
      kind: z.enum(['ADDED', 'REMOVED', 'CHANGED']),
      aspects: z.array(z.string()),
      tests: z.number(),
      testsFromEarlierVersion: z.number(),
      staleTests: z.number(),
    }),
  ),
  unchanged: z.number(),
  baseline: snapshotSchema.nullable(),
  current: z.record(z.string(), z.number()),
  history: z.array(snapshotSchema),
})
export type VersionChanges = z.infer<typeof changesSchema>

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/coverage`
const environmentQuery = (environmentId: string | undefined) => (environmentId ? `environmentId=${encodeURIComponent(environmentId)}` : '')

export const coverageKeys = {
  all: (projectId: string) => ['coverage', projectId] as const,
  report: (projectId: string, environmentId: string | undefined) => ['coverage', projectId, 'report', environmentId ?? 'all'] as const,
  operation: (projectId: string, key: string, environmentId: string | undefined) =>
    ['coverage', projectId, 'operation', key, environmentId ?? 'all'] as const,
  changes: (projectId: string) => ['coverage', projectId, 'changes'] as const,
}

export function useCoverage(projectId: string, environmentId: string | undefined) {
  return useQuery({
    queryKey: coverageKeys.report(projectId, environmentId),
    queryFn: ({ signal }) => {
      const query = environmentQuery(environmentId)
      return apiRequest(`${base(projectId)}${query ? `?${query}` : ''}`, coverageSchema, { signal })
    },
    placeholderData: keepPreviousData,
  })
}

export function useOperationCoverage(projectId: string, operationKey: string | undefined, environmentId: string | undefined) {
  return useQuery({
    queryKey: coverageKeys.operation(projectId, operationKey ?? '', environmentId),
    queryFn: ({ signal }) => {
      const query = environmentQuery(environmentId)
      return apiRequest(
        `${base(projectId)}/operation?key=${encodeURIComponent(operationKey ?? '')}${query ? `&${query}` : ''}`,
        operationCoverageSchema,
        { signal },
      )
    },
    enabled: operationKey !== undefined,
  })
}

export function useCoverageChanges(projectId: string, enabled: boolean) {
  return useQuery({
    queryKey: coverageKeys.changes(projectId),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/changes`, changesSchema, { signal }),
    enabled,
  })
}
