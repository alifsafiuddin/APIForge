import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest, noContent } from '@/api/client'
import { pageSchema } from '@/api/page'

export const projectSchema = z.object({
  id: z.string(),
  name: z.string(),
  description: z.string().nullable(),
  archived: z.boolean(),
  archivedAt: z.string().nullable(),
  activeSpecificationVersionId: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
  version: z.number(),
})
export type Project = z.infer<typeof projectSchema>

const projectPageSchema = pageSchema(projectSchema)

export type ProjectSort = 'updatedAt,desc' | 'name,asc' | 'createdAt,desc'

export type ProjectListParams = {
  page: number
  size: number
  archived: boolean
  query: string
  sort: ProjectSort
}

export const projectKeys = {
  all: ['projects'] as const,
  list: (params: ProjectListParams) => ['projects', 'list', params] as const,
}

export function useProjects(params: ProjectListParams) {
  return useQuery({
    queryKey: projectKeys.list(params),
    queryFn: ({ signal }) => {
      const search = new URLSearchParams({
        page: String(params.page),
        size: String(params.size),
        archived: String(params.archived),
        sort: params.sort,
      })
      if (params.query.trim()) {
        search.set('q', params.query.trim())
      }
      return apiRequest(`/api/v1/projects?${search.toString()}`, projectPageSchema, { signal })
    },
    placeholderData: keepPreviousData,
  })
}

export type ProjectInput = { name: string; description: string }

function useInvalidateProjects() {
  const queryClient = useQueryClient()
  return () => queryClient.invalidateQueries({ queryKey: projectKeys.all })
}

export function useCreateProject() {
  const invalidate = useInvalidateProjects()
  return useMutation({
    mutationFn: (input: ProjectInput) =>
      apiRequest('/api/v1/projects', projectSchema, { method: 'POST', body: input }),
    onSuccess: invalidate,
  })
}

export type ProjectUpdate = {
  id: string
  version: number
  name?: string
  description?: string
  archived?: boolean
}

export function useUpdateProject() {
  const invalidate = useInvalidateProjects()
  return useMutation({
    mutationFn: ({ id, ...body }: ProjectUpdate) =>
      apiRequest(`/api/v1/projects/${encodeURIComponent(id)}`, projectSchema, { method: 'PATCH', body }),
    onSettled: invalidate,
  })
}

export function useDeleteProject() {
  const invalidate = useInvalidateProjects()
  return useMutation({
    mutationFn: (id: string) =>
      apiRequest(`/api/v1/projects/${encodeURIComponent(id)}`, noContent, { method: 'DELETE' }),
    onSettled: invalidate,
  })
}
