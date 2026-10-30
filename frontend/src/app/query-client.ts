import { MutationCache, QueryCache, QueryClient } from '@tanstack/react-query'

import { ApiError } from '@/api/client'
import { authKeys } from '@/features/auth/api'
import { systemKeys } from '@/features/system/api'

const healthHash = JSON.stringify(systemKeys.health)

export function createQueryClient() {
  const queryClient: QueryClient = new QueryClient({
    // A 401 from any request means the session ended (expired, signed out elsewhere):
    // mark the user as signed out so the route guard sends them to the login page.
    queryCache: new QueryCache({
      onError: (error, query) => handleFailure(error, query.queryHash === healthHash),
    }),
    mutationCache: new MutationCache({ onError: (error) => handleFailure(error, false) }),
    defaultOptions: {
      queries: {
        // Retry transient failures once, but never client errors (4xx): they will not fix themselves.
        retry: (failureCount, error) =>
          !(error instanceof ApiError && error.status >= 400 && error.status < 500) && failureCount < 1,
        refetchOnWindowFocus: false,
      },
    },
  })

  function handleFailure(error: unknown, isHealthCheck: boolean) {
    if (!(error instanceof ApiError)) {
      return
    }
    if (error.status === 401 && error.code === 'UNAUTHENTICATED') {
      queryClient.setQueryData(authKeys.me, null)
    }
    // The backend did not answer: re-check health now so the connection banner appears without waiting for the
    // next poll. Not for the health check itself, which would re-check forever.
    if (error.code === 'NETWORK_ERROR' && !isHealthCheck && !queryClient.isFetching({ queryKey: systemKeys.health })) {
      void queryClient.invalidateQueries({ queryKey: systemKeys.health })
    }
  }

  return queryClient
}
