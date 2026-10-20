import { QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import { createMemoryRouter, RouterProvider } from 'react-router'

import { createQueryClient } from '@/app/query-client'
import { routes } from '@/app/routes'
import { Toaster } from '@/components/ui/sonner'

/**
 * Renders the real route tree at `path` with the real query client configuration
 * (including the global 401 handling), but without retries so failures surface immediately.
 */
export function renderApp(path = '/') {
  const queryClient = createQueryClient()
  queryClient.setDefaultOptions({ queries: { ...queryClient.getDefaultOptions().queries, retry: false } })
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  return {
    router,
    queryClient,
    ...render(
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
        <Toaster />
      </QueryClientProvider>,
    ),
  }
}
