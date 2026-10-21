import { History } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'

import { type Run, useRuns } from './api'
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

/** Execution history of the project, newest first. */
export function RunsPage() {
  const { projectId = '' } = useParams()
  const [page, setPage] = useState(0)
  const runs = useRuns(projectId, page)

  if (runs.isPending) return <Skeleton className="h-64" aria-label="Loading runs" />
  if (runs.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{runs.error.message}</AlertDescription>
      </Alert>
    )
  }
  if (runs.data.content.length === 0) {
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
  return (
    <div className="space-y-3">
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
                <OutcomeBar run={run} className="w-24" />
                <Counts run={run} />
                <span className="text-xs text-muted-foreground">{formatDateTime(run.createdAt)}</span>
              </Link>
            </li>
          ))}
        </ul>
      </Card>
      {runs.data.page.totalPages > 1 ? (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => setPage(page - 1)}>
            Newer
          </Button>
          <span>
            Page {page + 1} of {runs.data.page.totalPages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= runs.data.page.totalPages} onClick={() => setPage(page + 1)}>
            Older
          </Button>
        </div>
      ) : null}
    </div>
  )
}
