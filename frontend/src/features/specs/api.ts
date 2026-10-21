import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { ApiError, apiRequest } from '@/api/client'
import { pageSchema } from '@/api/page'
import { projectKeys } from '@/features/projects/api'

/** Free-form OpenAPI fragments (schemas, parameters, responses) as stored by the backend. */
const jsonObject = z.record(z.string(), z.unknown())
export type JsonObject = z.infer<typeof jsonObject>

export const importMessageSchema = z.object({
  severity: z.enum(['ERROR', 'WARNING', 'INFO']),
  code: z.string(),
  message: z.string(),
  location: z.string().optional(),
})
export type ImportMessage = z.infer<typeof importMessageSchema>

export const versionSummarySchema = z.object({
  id: z.string(),
  versionNumber: z.number(),
  title: z.string(),
  apiVersion: z.string().nullable(),
  openapiVersion: z.string(),
  sourceFormat: z.enum(['JSON', 'YAML']),
  sourceName: z.string().nullable(),
  operationCount: z.number(),
  warningCount: z.number(),
  active: z.boolean(),
  contentSha256: z.string(),
  contentSizeBytes: z.number(),
  importedAt: z.string(),
})
export type VersionSummary = z.infer<typeof versionSummarySchema>

const versionDetailSchema = z.object({
  summary: versionSummarySchema,
  description: z.string().nullable(),
  servers: z.array(jsonObject),
  securitySchemes: jsonObject,
  tags: z.array(jsonObject),
  messages: z.array(importMessageSchema),
})
export type VersionDetail = z.infer<typeof versionDetailSchema>

export const operationSummarySchema = z.object({
  id: z.string(),
  operationKey: z.string(),
  method: z.string(),
  path: z.string(),
  operationId: z.string().nullable(),
  summary: z.string().nullable(),
  tags: z.array(z.string()),
  deprecated: z.boolean(),
  warningCount: z.number(),
})
export type OperationSummary = z.infer<typeof operationSummarySchema>

const operationDetailSchema = operationSummarySchema.omit({ warningCount: true }).extend({
  specificationVersionId: z.string(),
  description: z.string().nullable(),
  parameters: z.array(jsonObject),
  requestBody: jsonObject.nullable(),
  responses: z.array(jsonObject),
  security: z.array(z.record(z.string(), z.array(z.string()))),
  warnings: z.array(importMessageSchema),
})
export type OperationDetail = z.infer<typeof operationDetailSchema>

const importResultSchema = z.object({ version: versionSummarySchema, messages: z.array(importMessageSchema) })
export type ImportResult = z.infer<typeof importResultSchema>

const validationResultSchema = z.object({
  valid: z.boolean(),
  openapiVersion: z.string().nullable(),
  title: z.string().nullable(),
  apiVersion: z.string().nullable(),
  operationCount: z.number().nullable(),
  messages: z.array(importMessageSchema),
})
export type ValidationResult = z.infer<typeof validationResultSchema>

/** Mirrors the server limit so oversized files are refused before uploading. */
export const MAX_SPEC_BYTES = 5 * 1024 * 1024

export const specKeys = {
  all: (projectId: string) => ['specs', projectId] as const,
  versions: (projectId: string) => ['specs', projectId, 'versions'] as const,
  version: (projectId: string, versionId: string) => ['specs', projectId, 'version', versionId] as const,
  operations: (projectId: string, versionId: string) => ['specs', projectId, 'operations', versionId] as const,
  operation: (projectId: string, versionId: string, operationId: string) =>
    ['specs', projectId, 'operation', versionId, operationId] as const,
  schemas: (projectId: string, versionId: string) => ['specs', projectId, 'schemas', versionId] as const,
}

const base = (projectId: string) => `/api/v1/projects/${encodeURIComponent(projectId)}/specs`

export function useSpecVersions(projectId: string) {
  return useQuery({
    queryKey: specKeys.versions(projectId),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}?size=100`, pageSchema(versionSummarySchema), { signal }),
  })
}

export function useSpecVersion(projectId: string, versionId: string | undefined) {
  return useQuery({
    queryKey: specKeys.version(projectId, versionId ?? ''),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/${versionId}`, versionDetailSchema, { signal }),
    enabled: versionId !== undefined,
    staleTime: Infinity, // versions are immutable
  })
}

export function useOperations(projectId: string, versionId: string | undefined) {
  return useQuery({
    queryKey: specKeys.operations(projectId, versionId ?? ''),
    queryFn: ({ signal }) =>
      apiRequest(`${base(projectId)}/${versionId}/operations`, z.array(operationSummarySchema), { signal }),
    enabled: versionId !== undefined,
    staleTime: Infinity,
  })
}

export function useOperation(projectId: string, versionId: string | undefined, operationId: string | undefined) {
  return useQuery({
    queryKey: specKeys.operation(projectId, versionId ?? '', operationId ?? ''),
    queryFn: ({ signal }) =>
      apiRequest(`${base(projectId)}/${versionId}/operations/${operationId}`, operationDetailSchema, { signal }),
    enabled: versionId !== undefined && operationId !== undefined,
    staleTime: Infinity,
  })
}

export function useComponentSchemas(projectId: string, versionId: string | undefined) {
  return useQuery({
    queryKey: specKeys.schemas(projectId, versionId ?? ''),
    queryFn: ({ signal }) => apiRequest(`${base(projectId)}/${versionId}/schemas`, jsonObject, { signal }),
    enabled: versionId !== undefined,
    staleTime: Infinity,
  })
}

export type SpecSource = { kind: 'file'; file: File } | { kind: 'text'; content: string }

function toBody(source: SpecSource) {
  if (source.kind === 'file') {
    const form = new FormData()
    form.append('file', source.file)
    return form
  }
  return { content: source.content, sourceName: 'Pasted text' }
}

export function useValidateSpec(projectId: string) {
  return useMutation({
    mutationFn: (source: SpecSource) =>
      apiRequest(`${base(projectId)}/validate`, validationResultSchema, { method: 'POST', body: toBody(source) }),
  })
}

export function useImportSpec(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ source, activate }: { source: SpecSource; activate: boolean }) =>
      apiRequest(`${base(projectId)}?activate=${activate}`, importResultSchema, { method: 'POST', body: toBody(source) }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: specKeys.versions(projectId) })
      await queryClient.invalidateQueries({ queryKey: projectKeys.all })
    },
  })
}

export function useActivateVersion(projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (versionId: string) =>
      apiRequest(`${base(projectId)}/${versionId}/activate`, versionSummarySchema, { method: 'POST' }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: specKeys.all(projectId) })
      await queryClient.invalidateQueries({ queryKey: projectKeys.all })
    },
  })
}

export function rawDownloadUrl(projectId: string, versionId: string) {
  return `${base(projectId)}/${versionId}/raw`
}

/** Extracts import messages from a rejected import (422 problem with a `messages` member). */
export function messagesFromError(error: unknown): ImportMessage[] {
  if (!(error instanceof ApiError)) {
    return []
  }
  const parsed = z.object({ messages: z.array(importMessageSchema) }).safeParse(error.problem)
  return parsed.success ? parsed.data.messages : []
}
