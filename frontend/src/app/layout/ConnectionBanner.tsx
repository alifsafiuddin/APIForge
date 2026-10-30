import { CloudOff, ServerCrash } from 'lucide-react'
import { useSyncExternalStore } from 'react'

import { Button } from '@/components/ui/button'
import { useBackendHealth } from '@/features/system/api'

function subscribe(onChange: () => void) {
  window.addEventListener('online', onChange)
  window.addEventListener('offline', onChange)
  return () => {
    window.removeEventListener('online', onChange)
    window.removeEventListener('offline', onChange)
  }
}

function useOnline(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => navigator.onLine,
    () => true,
  )
}

/**
 * Says when the browser is offline or the backend does not answer, so stale screens and failing
 * actions are explained. Disappears by itself when the connection is back (queries then refetch).
 */
export function ConnectionBanner() {
  const online = useOnline()
  const health = useBackendHealth()

  if (!online) {
    return (
      <output className="flex items-center gap-2 border-b bg-amber-50 px-4 py-2 text-sm text-amber-900 sm:px-6 dark:bg-amber-950 dark:text-amber-100">
        <CloudOff className="size-4 shrink-0" aria-hidden="true" />
        <span>You are offline. The data shown may be out of date, and changes cannot be saved until you reconnect.</span>
      </output>
    )
  }
  if (health.isError) {
    return (
      <div role="alert" className="flex flex-wrap items-center gap-2 border-b bg-red-50 px-4 py-2 text-sm text-red-900 sm:px-6 dark:bg-red-950 dark:text-red-100">
        <ServerCrash className="size-4 shrink-0" aria-hidden="true" />
        <span className="min-w-0 flex-1">
          The APIForge backend is not responding. The data shown may be out of date and actions will fail until it is back.
        </span>
        <Button size="sm" variant="outline" className="bg-transparent" onClick={() => void health.refetch()} disabled={health.isFetching}>
          {health.isFetching ? 'Checking…' : 'Check again'}
        </Button>
      </div>
    )
  }
  return null
}
