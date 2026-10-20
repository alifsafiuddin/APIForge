import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'

import { ApiError } from '@/api/client'
import { authKeys } from '@/features/auth/api'

export function createQueryClient() {
  const queryClient: QueryClient = new QueryClient({
    // A 401 from any request means the session ended (expired, signed out elsewhere):
    // mark the user as signed out so the route guard sends them to the login page.
    queryCache: new QueryCache({ onError: (error) => handleUnauthenticated(error) }),
    mutationCache: new MutationCache({ onError: (error) => handleUnauthenticated(error) }),
    defaultOptions: {
      queries: {
        // Retry transient failures once, but never client errors (4xx): they will not fix themselves.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 1,
        refetchOnWindowFocus: false,
      },
    },
  })

  function handleUnauthenticated(error: unknown) {
    if (error instanceof ApiError && error.status === 401 && error.code === 'UNAUTHENTICATED') {
      queryClient.setQueryData(authKeys.me, null)
    }
  }

  return queryClient
}
