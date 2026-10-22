import { useQuery } from '@tanstack/react-query'
import { z } from 'zod'

import { apiRequest } from '@/api/client'

export const systemInfoSchema = z.object({
  name: z.string(),
  version: z.string(),
  apiVersion: z.string(),
  serverTime: z.string(),
})
const healthStatusSchema = z.enum(['UP', 'DOWN', 'OUT_OF_SERVICE', 'UNKNOWN'])
export type HealthStatus = z.infer<typeof healthStatusSchema>

export const healthSchema = z.object({
  status: healthStatusSchema,
  components: z.record(z.string(), z.object({ status: healthStatusSchema })).optional(),
})
export const systemKeys = {
  info: ['system', 'info'] as const,
  health: ['system', 'health'] as const,
}

export function useSystemInfo() {
  return useQuery({
    queryKey: systemKeys.info,
    queryFn: ({ signal }) => apiRequest('/api/v1/system/info', systemInfoSchema, { signal }),
    staleTime: 60_000,
  })
}

/**
 * Backend health from Spring Boot Actuator. A 503 still carries a valid health document
 * (status DOWN plus components), so it is parsed rather than treated as a request failure.
 */
export function useBackendHealth() {
  return useQuery({
    queryKey: systemKeys.health,
    queryFn: ({ signal }) => apiRequest('/actuator/health', healthSchema, { signal, acceptStatuses: [503] }),
    refetchInterval: 30_000,
    retry: false,
  })
}
