import { QueryClient } from '@tanstack/react-query'

import { ApiError } from '@/api/client'

export function createQueryClient() {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Retry transient failures once, but never client errors (4xx): they will not fix themselves.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 1,
        refetchOnWindowFocus: false,
      },
    },
  })
}
