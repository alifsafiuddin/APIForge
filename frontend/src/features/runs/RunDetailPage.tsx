import { ArrowLeft, GitCompare } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/features/specs/components/MethodBadge'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

import { isActive, type Outcome, OUTCOMES, type Run, useCancelRun, useResult, useRun, useRunResults } from './api'
import { OutcomeBadge, ResultView } from './ResultView'
import { Counts, RunStatusBadge } from './RunsPage'

const COUNT_KEYS: Record<Outcome, keyof Run['counts']> = {
  PASSED: 'passed',
  FAILED: 'failed',
  INCONCLUSIVE: 'inconclusive',
  ERROR: 'error',
  SKIPPED: 'skipped',
}

const BAR_COLORS: Record<Outcome, string> = {
  PASSED: 'bg-emerald-500',
  FAILED: 'bg-red-500',
  ERROR: 'bg-orange-500',
  INCONCLUSIVE: 'bg-amber-400',
  SKIPPED: 'bg-slate-400',
}

/** Results by outcome as one stacked bar; tests not yet executed stay grey. */
export function OutcomeBar({ run, className }: { run: Run; className?: string }) {
  const total = Math.max(run.counts.total, 1)
  return (
    <div className={cn('flex h-2 w-full overflow-hidden rounded bg-muted', className)} aria-hidden="true">
      {(['PASSED', 'FAILED', 'ERROR', 'INCONCLUSIVE', 'SKIPPED'] as const).map((outcome) => (
        <div key={outcome} className={BAR_COLORS[outcome]} style={{ width: `${(run.counts[COUNT_KEYS[outcome]] / total) * 100}%` }} />
      ))}
    </div>
  )
}

const FILTERS: (Outcome | undefined)[] = [undefined, 'FAILED', 'ERROR', 'INCONCLUSIVE', 'SKIPPED', 'PASSED']

/** One run: live progress while it runs, then its results by outcome with request, response and assertions. */
export function RunDetailPage() {
  const { projectId = '', runId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const outcomeParam = searchParams.get('outcome')
  const outcome = OUTCOMES.find((value) => value === outcomeParam)
  const page = Math.max(0, Number(searchParams.get('page')) || 0)
  const run = useRun(projectId, runId)
  const active = run.data ? isActive(run.data.status) : false
  const results = useRunResults(projectId, runId, active, outcome, page)
  const cancel = useCancelRun(projectId, runId)
  const selected = searchParams.get('result') ?? undefined

  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined) next.delete(key)
      else next.set(key, value)
    }
    setSearchParams(next)
  }

  if (run.isPending) return <Skeleton className="h-64" aria-label="Loading run" />
  if (run.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{run.error.message}</AlertDescription>
      </Alert>
    )
  }
  const { counts } = run.data
  const done = counts.passed + counts.failed + counts.error + counts.skipped + counts.inconclusive

  const stop = async () => {
    try {
      await cancel.mutateAsync()
      toast.success('Cancelling: the request in flight is aborted and the remaining tests are skipped.')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The run could not be cancelled.')
    }
  }

  const rows = results.data?.content ?? []
  const slowest = Math.max(1, ...rows.map((result) => result.durationMs ?? 0))
  const totalPages = results.data?.page.totalPages ?? 0

  return (
    <div className="space-y-4">
      <Link to={`/projects/${projectId}/runs`} className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
        <ArrowLeft className="size-4" aria-hidden="true" />
        All runs
      </Link>
      <Card className="gap-2 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="font-medium">{run.data.kind === 'AD_HOC' ? 'Console request' : 'Test run'}</h2>
          <RunStatusBadge run={run.data} />
          <span className="flex-1" />
          {!active && run.data.kind === 'TEST_RUN' ? (
            <Button size="sm" variant="outline" asChild>
              <Link to={`/projects/${projectId}/runs/${runId}/compare`}>
                <GitCompare className="size-4" aria-hidden="true" />
                Compare with previous run
              </Link>
            </Button>
          ) : null}
          {active ? (
            <Button size="sm" variant="destructive" onClick={() => void stop()} disabled={cancel.isPending}>
              Cancel run
            </Button>
          ) : null}
        </div>
        <p className="text-sm text-muted-foreground">
          {run.data.environmentName ?? 'deleted environment'} · <span className="font-mono">{run.data.baseUrl}</span> · started{' '}
          {formatDateTime(run.data.startedAt ?? run.data.createdAt)}
        </p>
        {run.data.statusReason ? <p className="text-sm">{run.data.statusReason}</p> : null}
        <progress aria-label="Run progress" className="sr-only" max={Math.max(counts.total, 1)} value={done} />
        <OutcomeBar run={run.data} />
        <Counts run={run.data} />
      </Card>

      <fieldset className="flex flex-wrap gap-1.5">
        <legend className="sr-only">Show results</legend>
        {FILTERS.map((filter) => {
          const count = filter ? counts[COUNT_KEYS[filter]] : done
          const label = filter ? filter.charAt(0) + filter.slice(1).toLowerCase() : 'All'
          return (
            <Button
              key={filter ?? 'ALL'}
              size="sm"
              variant={filter === outcome ? 'default' : 'outline'}
              aria-pressed={filter === outcome}
              disabled={filter !== undefined && count === 0 && filter !== outcome}
              onClick={() => update({ outcome: filter, page: undefined, result: undefined })}
            >
              {label} <span className="tabular-nums opacity-70">{count}</span>
            </Button>
          )
        })}
      </fieldset>

      <Card className="gap-0 p-0">
        {results.isPending ? (
          <Skeleton className="m-3 h-24" />
        ) : results.isError ? (
          <p className="p-4 text-sm text-destructive">{results.error.message}</p>
        ) : rows.length === 0 ? (
          <p className="p-6 text-center text-sm text-muted-foreground">
            {active ? 'Waiting for results…' : outcome ? `No ${outcome.toLowerCase()} results.` : 'No results.'}
          </p>
        ) : (
          <ul aria-label="Results" className="divide-y">
            {rows.map((result) => (
              <li key={result.id}>
                <button
                  type="button"
                  className="grid w-full grid-cols-[auto_1fr_auto] items-center gap-x-3 gap-y-0.5 px-4 py-2 text-left hover:bg-muted/50"
                  onClick={() => update({ result: result.id })}
                >
                  <OutcomeBadge outcome={result.outcome} />
                  <span className="min-w-0 truncate text-sm">{result.testName ?? result.operationKey}</span>
                  <span className="flex items-center gap-2">
                    {result.responseStatus !== null ? <StatusBadge status={String(result.responseStatus)} /> : null}
                    <span className="hidden w-20 sm:block" aria-hidden="true">
                      <span className="block h-1.5 rounded bg-sky-400/70" style={{ width: `${((result.durationMs ?? 0) / slowest) * 100}%` }} />
                    </span>
                    <span className="w-14 text-right text-xs text-muted-foreground tabular-nums">
                      {result.durationMs !== null ? `${result.durationMs} ms` : '–'}
                    </span>
                  </span>
                  <span />
                  <span className="col-span-2 min-w-0 truncate text-xs text-muted-foreground">
                    <span className="font-mono">{result.operationKey}</span>
                    {result.firstFailure ? (
                      <span className="text-red-700 dark:text-red-400">
                        {' · '}
                        {result.firstFailure}
                        {result.failedChecks > 1 ? ` (+${result.failedChecks - 1} more)` : ''}
                      </span>
                    ) : null}
                    {result.errorKind ? <span className="text-destructive"> · {result.errorKind}</span> : null}
                    {result.outcome === 'SKIPPED' && result.errorMessage ? <span> · {result.errorMessage}</span> : null}
                    {result.skippedChecks > 0 && result.outcome !== 'SKIPPED' ? (
                      <span>
                        {' · '}
                        {result.skippedChecks} {result.skippedChecks === 1 ? 'check' : 'checks'} skipped
                      </span>
                    ) : null}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {totalPages > 1 ? (
        <div className="flex items-center justify-end gap-2 text-sm">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => update({ page: String(page - 1) })}>
            Previous
          </Button>
          <span>
            Page {page + 1} of {totalPages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= totalPages} onClick={() => update({ page: String(page + 1) })}>
            Next
          </Button>
        </div>
      ) : null}

      <ResultSheet projectId={projectId} runId={runId} resultId={selected} onClose={() => update({ result: undefined })} />
    </div>
  )
}

function ResultSheet({ projectId, runId, resultId, onClose }: { projectId: string; runId: string; resultId: string | undefined; onClose: () => void }) {
  const result = useResult(projectId, runId, resultId)
  return (
    <Sheet open={resultId !== undefined} onOpenChange={(open) => (open ? undefined : onClose())}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-3xl">
        <SheetHeader className="pr-10">
          <SheetTitle>{result.data?.testName ?? 'Result'}</SheetTitle>
          <SheetDescription className="font-mono text-xs">{result.data?.operationKey}</SheetDescription>
        </SheetHeader>
        <div className="px-4 pb-6">
          {result.isPending ? <Skeleton className="h-40" /> : result.isError ? <p className="text-destructive">{result.error.message}</p> : <ResultView result={result.data} projectId={projectId} />}
        </div>
      </SheetContent>
    </Sheet>
  )
}
