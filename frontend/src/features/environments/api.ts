import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest, noContent } from '@/api/client'

export const POLICIES = ['READ_ONLY', 'ALLOW_MUTATING', 'ALLOW_DESTRUCTIVE'] as const
export type Policy = (typeof POLICIES)[number]
export const POLICY_LABELS: Record<Policy, string> = {
  READ_ONLY: 'Read-only (GET, HEAD, OPTIONS)',
  ALLOW_MUTATING: 'Allow mutating (adds POST, PUT, PATCH)',
  ALLOW_DESTRUCTIVE: 'Allow destructive (adds DELETE)',
}

export const AUTH_TYPES = ['NONE', 'BEARER', 'BASIC', 'API_KEY_HEADER', 'API_KEY_QUERY'] as const
export type AuthType = (typeof AUTH_TYPES)[number]
export const AUTH_LABELS: Record<AuthType, string> = {
  NONE: 'None',
  BEARER: 'Bearer token',
  BASIC: 'Basic (username and password)',
  API_KEY_HEADER: 'API key in a header',
  API_KEY_QUERY: 'API key in a query parameter',
}

const variableSchema = z.object({
  name: z.string(),
  secret: z.boolean(),
  value: z.string().nullable(),
  hasValue: z.boolean(),
  updatedAt: z.string(),
})
export const environmentSchema = z.object({
  id: z.string(),
  name: z.string(),
  baseUrl: z.string(),
  timeoutMs: z.number(),
  followRedirects: z.boolean(),
  executionPolicy: z.enum(POLICIES),
  auth: z.object({ type: z.enum(AUTH_TYPES), config: z.record(z.string(), z.unknown()) }),
  defaultHeaders: z.record(z.string(), z.string()),
  variables: z.array(variableSchema),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number(),
})
export type EnvironmentDetail = z.infer<typeof environmentSchema>

export type AuthInput = {
  type: AuthType
  tokenVariable?: string
  usernameVariable?: string
  passwordVariable?: string
  headerName?: string
  parameterName?: string
  valueVariable?: string
}

export type EnvironmentInput = {
  name: string
  baseUrl: string
  timeoutMs: number
  followRedirects: boolean
  executionPolicy: Policy
  authorizationConfirmed: boolean
  auth: AuthInput
  defaultHeaders: Record<string, string>
  version?: number
}

export const environmentKeys = {
  all: (projectId: string) => ['environments', projectId] as const,
}

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/environments`

export function useEnvironments(projectId: string) {
  return useQuery({
    queryKey: environmentKeys.all(projectId),
    queryFn: ({ signal }) => apiRequest(base(projectId), z.array(environmentSchema), { signal }),
  })
}

function useInvalidate(projectId: string) {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: environmentKeys.all(projectId) })
}

export function useSaveEnvironment(projectId: string) {
  const invalidate = useInvalidate(projectId)
  return useMutation({
    mutationFn: ({ id, input }: { id?: string; input: EnvironmentInput }) =>
      apiRequest(id ? `${base(projectId)}/${id}` : base(projectId), environmentSchema, {
        method: id ? 'PUT' : 'POST',
        body: input,
      }),
    onSuccess: invalidate,
  })
}

export function useDeleteEnvironment(projectId: string) {
  const invalidate = useInvalidate(projectId)
  return useMutation({
    mutationFn: (id: string) => apiRequest(`${base(projectId)}/${id}`, noContent, { method: 'DELETE' }),
    onSuccess: invalidate,
  })
}

export function usePutVariable(projectId: string, environmentId: string) {
  const invalidate = useInvalidate(projectId)
  return useMutation({
    mutationFn: ({ name, value, secret }: { name: string; value: string; secret: boolean }) =>
      apiRequest(`${base(projectId)}/${environmentId}/variables/${encodeURIComponent(name)}`, variableSchema, {
        method: 'PUT',
        body: { value, secret },
      }),
    onSuccess: invalidate,
  })
}

export function useDeleteVariable(projectId: string, environmentId: string) {
  const invalidate = useInvalidate(projectId)
  return useMutation({
    mutationFn: (name: string) =>
      apiRequest(`${base(projectId)}/${environmentId}/variables/${encodeURIComponent(name)}`, noContent, {
        method: 'DELETE',
      }),
    onSuccess: invalidate,
  })
}
