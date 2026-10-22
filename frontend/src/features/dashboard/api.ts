import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest } from '@/api/client'

const runRefSchema = z.object({
  id: z.string(),
  projectId: z.string(),
  projectName: z.string(),
  status: z.enum(['QUEUED', 'RUNNING', 'COMPLETED', 'CANCELLED', 'INTERRUPTED']),
  environmentName: z.string().nullable(),
  total: z.number(),
  passed: z.number(),
  failed: z.number(),
  error: z.number(),
  inconclusive: z.number(),
  skipped: z.number(),
  createdAt: z.string(),
  finishedAt: z.string().nullable(),
})
export type RunRef = z.infer<typeof runRefSchema>

const projectSummarySchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  archived: z.boolean(),
  updatedAt: z.string(),
  activeSpecification: z
    .object({
      id: z.string(),
      versionNumber: z.number(),
      title: z.string(),
      apiVersion: z.string().nullable(),
      operations: z.number(),
    })
    .nullable(),
  tests: z.object({
    active: z.number(),
    enabled: z.number(),
    pendingReview: z.number(),
    stale: z.number(),
    suites: z.number(),
  }),
  environments: z.number(),
  lastRun: runRefSchema.nullable(),
  /** The latest stored coverage snapshot of the active version (not recalculated). */
  coverage: z
    .object({
      takenAt: z.string(),
      operations: z.number(),
      operationsWithTests: z.number(),
      responses: z.number(),
      responsesExercised: z.number(),
      executed: z.number(),
      passed: z.number(),
    })
    .nullable(),
})
export type ProjectSummary = z.infer<typeof projectSummarySchema>

const dashboardSchema = z.object({
  totals: z.object({
    activeProjects: z.number(),
    archivedProjects: z.number(),
    runsLast7Days: z.number(),
    runsWithFailuresLast7Days: z.number(),
    runsInProgress: z.number(),
    testsPendingReview: z.number(),
    staleTests: z.number(),
  }),
  projects: z.array(projectSummarySchema),
  recentRuns: z.array(runRefSchema),
})
export type Dashboard = z.infer<typeof dashboardSchema>

const overviewSchema = z.object({
  project: projectSummarySchema,
  recentRuns: z.array(runRefSchema),
})

export const dashboardKeys = {
  dashboard: ['dashboard'] as const,
  overview: (projectId: string) => ['dashboard', 'project', projectId] as const,
}

const hasActiveRun = (runs: RunRef[]) => runs.some((run) => run.status === 'QUEUED' || run.status === 'RUNNING')

/** Totals, the most recently active projects and the latest runs; refreshes while a run is in progress. */
export function useDashboard() {
  return useQuery({
    queryKey: dashboardKeys.dashboard,
    queryFn: ({ signal }) => apiRequest('/api/v1/dashboard', dashboardSchema, { signal }),
    refetchInterval: (query) => (query.state.data && hasActiveRun(query.state.data.recentRuns) ? 3000 : false),
  })
}

export function useProjectOverview(projectId: string) {
  return useQuery({
    queryKey: dashboardKeys.overview(projectId),
    queryFn: ({ signal }) =>
      apiRequest(`/api/v1/projects/${encodeURIComponent(projectId)}/overview`, overviewSchema, { signal }),
    refetchInterval: (query) => (query.state.data && hasActiveRun(query.state.data.recentRuns) ? 3000 : false),
  })
}

/** A whole-number percentage, or "—" when there is nothing to measure. */
export function percent(value: number, total: number): string {
  return total === 0 ? '—' : `${Math.round((value / total) * 100)}%`
}

export function runCounts(run: RunRef) {
  return {
    total: run.total,
    passed: run.passed,
    failed: run.failed,
    error: run.error,
    inconclusive: run.inconclusive,
    skipped: run.skipped,
  }
}

/** "40 of 48 passed, 6 failed" — the outcome counts that matter at a glance. */
export function runOutcomeText(run: RunRef): string {
  const parts = [`${run.passed} of ${run.total} passed`]
  if (run.failed) parts.push(`${run.failed} failed`)
  if (run.error) parts.push(`${run.error} errors`)
  if (run.inconclusive) parts.push(`${run.inconclusive} inconclusive`)
  if (run.skipped) parts.push(`${run.skipped} skipped`)
  return parts.join(', ')
}
