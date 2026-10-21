import { ArrowLeft, ArrowRight } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { ApiError } from '@/api/client'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

import { CHANGE_KINDS, type ChangeKind, type Run, useRunComparison, useRuns } from './api'
import { OutcomeBar } from './RunDetailPage'
import { OutcomeBadge } from './ResultView'
import { Counts } from './RunsPage'

const KIND_STYLES: Record<ChangeKind, string> = {
  REGRESSED: 'bg-red-100 text-red-800 ring-red-200',
  FIXED: 'bg-emerald-100 text-emerald-800 ring-emerald-200',
  CHANGED: 'bg-amber-100 text-amber-900 ring-amber-200',
  NEW: 'bg-sky-100 text-sky-800 ring-sky-200',
  REMOVED: 'bg-muted text-muted-foreground ring-border',
  UNCHANGED: 'bg-muted text-muted-foreground ring-border',
}

const KIND_HELP: Record<ChangeKind, string> = {
  REGRESSED: 'passed before, not now',
  FIXED: 'passes now, did not before',
  CHANGED: 'another outcome change',
  NEW: 'only in this run',
  REMOVED: 'only in the baseline',
  UNCHANGED: 'same outcome',
}

function RunCard({ title, run, projectId }: { title: string; run: Run; projectId: string }) {
  return (
    <Card className="gap-1.5 p-3">
      <p className="text-xs text-muted-foreground">{title}</p>
      <Link to={`/projects/${projectId}/runs/${run.id}`} className="text-sm font-medium hover:underline">
        {formatDateTime(run.startedAt ?? run.createdAt)}
      </Link>
      <p className="truncate text-xs text-muted-foreground">
        {run.environmentName ?? 'deleted environment'} · {run.baseUrl}
      </p>
      <OutcomeBar run={run} />
      <Counts run={run} />
    </Card>
  )
}

/** Outcome changes of each test between a baseline run and this run. */
export function RunComparisonPage() {
  const { projectId = '', runId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const baseline = searchParams.get('baseline') ?? undefined
  const comparison = useRunComparison(projectId, runId, baseline)
  const runs = useRuns(projectId, 0)
  const [showUnchanged, setShowUnchanged] = useState(false)
  const candidates = (runs.data?.content ?? []).filter((run) => run.kind === 'TEST_RUN' && run.id !== runId)

  const back = (
    <Link to={`/projects/${projectId}/runs/${runId}`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
      <ArrowLeft className="size-4" aria-hidden="true" />
      Back to the run
    </Link>
  )
  const picker = (
    <div className="flex flex-wrap items-center gap-2">
      <Label htmlFor="baseline">Baseline run</Label>
      <select
        id="baseline"
        className="h-8 w-full min-w-0 rounded-md border bg-background px-2 text-sm sm:w-auto sm:max-w-md"
        value={baseline ?? ''}
        onChange={(event) => setSearchParams(event.target.value ? { baseline: event.target.value } : {})}
      >
        <option value="">Previous test run</option>
        {candidates.map((run) => (
          <option key={run.id} value={run.id}>
            {formatDateTime(run.createdAt)} · {run.environmentName ?? 'deleted environment'} · {run.counts.passed}/{run.counts.total} passed
          </option>
        ))}
      </select>
    </div>
  )

  if (comparison.isPending) return <Skeleton className="h-64" aria-label="Loading comparison" />
  if (comparison.isError) {
    const none = comparison.error instanceof ApiError && comparison.error.status === 404 && baseline === undefined
    return (
      <div className="space-y-4">
        {back}
        {picker}
        <Alert variant={none ? 'default' : 'destructive'}>
          <AlertDescription>{none ? 'There is no earlier test run in this project to compare with.' : comparison.error.message}</AlertDescription>
        </Alert>
      </div>
    )
  }
  const { counts, changes } = comparison.data
  const visible = showUnchanged ? changes : changes.filter((change) => change.kind !== 'UNCHANGED')
  return (
    <div className="space-y-4">
      {back}
      <h2 className="font-medium">Run comparison</h2>
      {picker}
      <div className="grid gap-3 sm:grid-cols-2">
        <RunCard title="Baseline" run={comparison.data.baseline} projectId={projectId} />
        <RunCard title="This run" run={comparison.data.current} projectId={projectId} />
      </div>
      <ul aria-label="Change counts" className="flex flex-wrap gap-2">
        {CHANGE_KINDS.map((kind) => (
          <li key={kind} className={cn('rounded px-2 py-1 text-xs ring-1 ring-inset', KIND_STYLES[kind])} title={KIND_HELP[kind]}>
            <span className="font-semibold tabular-nums">{counts[kind] ?? 0}</span> {kind.toLowerCase()}
          </li>
        ))}
      </ul>
      <Card className="gap-0 p-0">
        {visible.length === 0 ? (
          <p className="p-6 text-center text-sm text-muted-foreground">Every test has the same outcome in both runs.</p>
        ) : (
          <ul aria-label="Changes" className="divide-y">
            {visible.map((change, position) => (
              <li key={position} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2">
                <span className={cn('w-24 shrink-0 rounded px-1.5 py-0.5 text-center text-xs font-semibold ring-1 ring-inset', KIND_STYLES[change.kind])}>
                  {change.kind}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm">{change.testName ?? change.operationKey}</span>
                  <span className="block truncate font-mono text-xs text-muted-foreground">{change.operationKey}</span>
                </span>
                <span className="flex items-center gap-1.5">
                  {change.baselineOutcome && change.baselineResultId ? (
                    <Link to={`/projects/${projectId}/runs/${comparison.data.baseline.id}?result=${change.baselineResultId}`} aria-label="Result in the baseline run">
                      <OutcomeBadge outcome={change.baselineOutcome} />
                    </Link>
                  ) : (
                    <span className="text-xs text-muted-foreground">absent</span>
                  )}
                  <ArrowRight className="size-3.5 text-muted-foreground" aria-hidden="true" />
                  {change.currentOutcome && change.currentResultId ? (
                    <Link to={`/projects/${projectId}/runs/${runId}?result=${change.currentResultId}`} aria-label="Result in this run">
                      <OutcomeBadge outcome={change.currentOutcome} />
                    </Link>
                  ) : (
                    <span className="text-xs text-muted-foreground">absent</span>
                  )}
                </span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {(counts.UNCHANGED ?? 0) > 0 ? (
        <Button size="sm" variant="ghost" onClick={() => setShowUnchanged(!showUnchanged)}>
          {showUnchanged ? 'Hide' : 'Show'} {counts.UNCHANGED} unchanged
        </Button>
      ) : null}
    </div>
  )
}
