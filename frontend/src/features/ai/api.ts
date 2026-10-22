import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { ApiError, apiRequest } from '@/api/client'
import { testDetailSchema, testKeys } from '@/features/tests/api'

export const AI_STATES = ['READY', 'MODEL_MISSING', 'UNAVAILABLE', 'DISABLED'] as const
export type AiState = (typeof AI_STATES)[number]

export const aiStatusSchema = z.object({
  enabled: z.boolean(),
  state: z.enum(AI_STATES),
  model: z.string(),
  server: z.string(),
  installedModels: z.array(z.string()),
  message: z.string(),
  latencyMs: z.number().nullable(),
  checkedAt: z.string(),
})
export type AiStatus = z.infer<typeof aiStatusSchema>

export const suggestionReportSchema = z.object({
  operationKey: z.string(),
  model: z.string(),
  latencyMs: z.number(),
  attempts: z.number(),
  created: z.array(testDetailSchema),
  discarded: z.array(z.object({ name: z.string(), reason: z.string() })),
  possibleDuplicates: z.array(z.object({ name: z.string(), similarTo: z.string(), difference: z.string() })),
})
export type SuggestionReport = z.infer<typeof suggestionReportSchema>

const GROUNDING = ['VERIFIED', 'UNVERIFIED', 'NOT_CHECKABLE'] as const
export type Grounding = (typeof GROUNDING)[number]

export const explanationSchema = z.object({
  id: z.string(),
  resultId: z.string(),
  model: z.string(),
  latencyMs: z.number(),
  createdAt: z.string(),
  summary: z.string(),
  observedFacts: z.array(z.object({ statement: z.string(), source: z.string(), grounding: z.enum(GROUNDING) })),
  likelyCauses: z.array(
    z.object({
      hypothesis: z.string(),
      confidence: z.string(),
      evidence: z.array(z.object({ text: z.string(), grounding: z.enum(GROUNDING) })),
    }),
  ),
  debuggingSteps: z.array(z.string()),
  suggestedChanges: z.array(z.object({ target: z.string(), change: z.string() })),
  inputs: z.array(z.string()),
  sourceCodeProvided: z.boolean(),
  warnings: z.array(z.string()),
})
export type Explanation = z.infer<typeof explanationSchema>

export const aiKeys = {
  status: ['ai', 'status'] as const,
  explanation: (projectId: string, resultId: string) => ['ai', projectId, 'explanation', resultId] as const,
}

/** Polled every minute; "Check again" refreshes immediately. */
export function useAiStatus() {
  return useQuery({
    queryKey: aiKeys.status,
    queryFn: ({ signal }) => apiRequest('/api/v1/ai/status', aiStatusSchema, { signal }),
    refetchInterval: 60_000,
    staleTime: 30_000,
  })
}

export function useRefreshAiStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiRequest('/api/v1/ai/status?refresh=true', aiStatusSchema),
    onSuccess: (status) => queryClient.setQueryData(aiKeys.status, status),
  })
}

export function useSuggestTests(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (operationKey: string) =>
      apiRequest(`/api/v1/projects/${encodeURIComponent(projectId)}/ai/suggestions`, suggestionReportSchema, {
        method: 'POST',
        body: { operationKey },
      }),
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: testKeys.all(projectId) })
      await queryClient.invalidateQueries({ queryKey: aiKeys.status })
    },
  })
}

const resultBase = (projectId: string, runId: string, resultId: string) =>
  `/api/v1/projects/${encodeURIComponent(projectId)}/runs/${runId}/results/${resultId}/explanation`

/** The latest stored explanation, or null when the result was never explained. */
export function useExplanation(projectId: string, runId: string, resultId: string, enabled: boolean) {
  return useQuery({
    queryKey: aiKeys.explanation(projectId, resultId),
    queryFn: async ({ signal }) => {
      try {
        return await apiRequest(resultBase(projectId, runId, resultId), explanationSchema, { signal })
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return null
        throw error
      }
    },
    enabled,
  })
}

export function useExplainFailure(projectId: string, runId: string, resultId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiRequest(resultBase(projectId, runId, resultId), explanationSchema, { method: 'POST' }),
    onSuccess: (explanation) => queryClient.setQueryData(aiKeys.explanation(projectId, resultId), explanation),
    onSettled: () => queryClient.invalidateQueries({ queryKey: aiKeys.status }),
  })
}
