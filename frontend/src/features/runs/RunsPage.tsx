import { History } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'

import { type Run, RUN_STATUSES, type RunStatus, useRuns } from './api'
import { OutcomeBar } from './RunDetailPage'

export function RunStatusBadge({ run }: { run: Run }) {
  const variant = run.status === 'COMPLETED' ? 'secondary' : run.status === 'RUNNING' || run.status === 'QUEUED' ? 'default' : 'destructive'
  return <Badge variant={variant}>{run.status}</Badge>
}

export function Counts({ run }: { run: Run }) {
  const { counts } = run
  return (
    <span className="text-xs text-muted-foreground">
      {counts.passed} passed · {counts.failed} failed · {counts.error} errors · {counts.skipped} skipped
      {counts.inconclusive ? ` · ${counts.inconclusive} inconclusive` : ''} of {counts.total}
    </span>
  )
}

const SELECT_CLASS =
  'h-8 min-w-0 rounded-lg border border-input bg-background px-2.5 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none'

const STATUS_LABELS: Record<RunStatus, string> = {
  QUEUED: 'Queued',
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
  INTERRUPTED: 'Interrupted',
}

/** Execution history of the project, newest first, filtered by kind, status and result (kept in the URL). */
export function RunsPage() {
  const { projectId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const kindParam = searchParams.get('kind')
  const kind = kindParam === 'TEST_RUN' || kindParam === 'AD_HOC' ? kindParam : undefined
  const status = RUN_STATUSES.find((value) => value === searchParams.get('status'))
  const failingParam = searchParams.get('result')
  const failing = failingParam === 'failing' ? true : failingParam === 'clean' ? false : undefined
  const page = Math.max(0, Number(searchParams.get('page')) || 0)
  const filtered = kind !== undefined || status !== undefined || failing !== undefined
  const runs = useRuns(projectId, page, { kind, status, failing })

  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    if (!('page' in changes)) next.delete('page')
    setSearchParams(next)
  }

  const filters = (
    <fieldset className="m-0 flex min-w-0 flex-wrap items-center gap-2 border-0 p-0">
      <legend className="sr-only">Filter runs</legend>
      <select aria-label="Kind" className={SELECT_CLASS} value={kind ?? ''} onChange={(event) => update({ kind: event.target.value })}>
        <option value="">Test runs and console requests</option>
        <option value="TEST_RUN">Test runs</option>
        <option value="AD_HOC">Console requests</option>
      </select>
      <select aria-label="Status" className={SELECT_CLASS} value={status ?? ''} onChange={(event) => update({ status: event.target.value })}>
        <option value="">Any status</option>
        {RUN_STATUSES.map((value) => (
          <option key={value} value={value}>
            {STATUS_LABELS[value]}
          </option>
        ))}
      </select>
      <select
        aria-label="Result"
        className={SELECT_CLASS}
        value={failingParam === 'failing' || failingParam === 'clean' ? failingParam : ''}
        onChange={(event) => update({ result: event.target.value })}
      >
        <option value="">Any result</option>
        <option value="failing">With failures or errors</option>
        <option value="clean">Completed without failures</option>
      </select>
      {filtered ? (
        <Button variant="ghost" size="sm" onClick={() => update({ kind: undefined, status: undefined, result: undefined })}>
          Clear filters
        </Button>
      ) : null}
    </fieldset>
  )

  if (runs.isPending) return <Skeleton className="h-64" aria-label="Loading runs" />
  if (runs.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription className="space-y-2">
          <p>{runs.error.message}</p>
          <Button variant="outline" size="sm" onClick={() => void runs.refetch()}>
            Try again
          </Button>
        </AlertDescription>
      </Alert>
    )
  }
  if (runs.data.content.length === 0 && !filtered && page === 0) {
    return (
      <Card className="items-center gap-3 px-6 py-12 text-center">
        <History className="size-8 text-muted-foreground" aria-hidden="true" />
        <h2 className="font-medium">No runs yet</h2>
        <p className="max-w-md text-sm text-muted-foreground">
          Run tests from the <Link className="underline" to={`/projects/${projectId}/tests`}>Tests</Link> tab or send a
          request from the <Link className="underline" to={`/projects/${projectId}/console`}>Console</Link>.
        </p>
      </Card>
    )
  }
  const { totalElements, totalPages } = runs.data.page
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        {filters}
        <span className="text-xs text-muted-foreground" aria-live="polite">
          {totalElements} {totalElements === 1 ? 'run' : 'runs'}
          {runs.isFetching ? ' · updating…' : ''}
        </span>
      </div>
      {runs.data.content.length === 0 ? (
        <Card className="items-center px-6 py-10 text-center text-sm text-muted-foreground">No runs match these filters.</Card>
      ) : (
        <Card className="gap-0 p-0">
          <ul aria-label="Runs" className="divide-y">
            {runs.data.content.map((run) => (
              <li key={run.id}>
                <Link to={`/projects/${projectId}/runs/${run.id}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 hover:bg-muted/50">
                  <RunStatusBadge run={run} />
                  <span className="text-sm font-medium">{run.kind === 'AD_HOC' ? 'Console request' : 'Test run'}</span>
                  <span className="min-w-0 truncate text-xs text-muted-foreground">
                    {run.environmentName ?? 'deleted environment'} · {run.baseUrl}
                  </span>
                  <span className="flex-1" />
                  <OutcomeBar counts={run.counts} className="w-24" />
                  <Counts run={run} />
                  <span className="text-xs text-muted-foreground">{formatDateTime(run.createdAt)}</span>
                </Link>
              </li>
            ))}
          </ul>
        </Card>
      )}
      {totalPages > 1 ? (
        <nav aria-label="Run pages" className="flex items-center justify-end gap-2 text-sm">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => update({ page: page - 1 > 0 ? String(page - 1) : undefined })}>
            Newer
          </Button>
          <span>
            Page {page + 1} of {totalPages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= totalPages} onClick={() => update({ page: String(page + 1) })}>
            Older
          </Button>
        </nav>
      ) : null}
    </div>
  )
}
