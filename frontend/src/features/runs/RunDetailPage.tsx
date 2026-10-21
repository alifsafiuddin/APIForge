import { ArrowLeft } from 'lucide-react'
import { Link, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Skeleton } from '@/components/ui/skeleton'
import { StatusBadge } from '@/features/specs/components/MethodBadge'
import { formatDateTime } from '@/lib/format'

import { isActive, useCancelRun, useResult, useRun, useRunResults } from './api'
import { OutcomeBadge, ResultView } from './ResultView'
import { Counts, RunStatusBadge } from './RunsPage'

/** One run: live progress while it runs, then every result with its request and response. */
export function RunDetailPage() {
  const { projectId = '', runId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const run = useRun(projectId, runId)
  const active = run.data ? isActive(run.data.status) : false
  const results = useRunResults(projectId, runId, active)
  const cancel = useCancelRun(projectId, runId)
  const selected = searchParams.get('result') ?? undefined

  if (run.isPending) return <Skeleton className="h-64" aria-label="Loading run" />
  if (run.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{run.error.message}</AlertDescription>
      </Alert>
    )
  }
  const done = run.data.counts.passed + run.data.counts.failed + run.data.counts.error + run.data.counts.skipped + run.data.counts.inconclusive

  const stop = async () => {
    try {
      await cancel.mutateAsync()
      toast.success('Cancelling: the request in flight is aborted and the remaining tests are skipped.')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The run could not be cancelled.')
    }
  }

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
        <progress
          aria-label="Run progress"
          className="h-2 w-full overflow-hidden rounded [&::-moz-progress-bar]:bg-primary [&::-webkit-progress-bar]:bg-muted [&::-webkit-progress-value]:bg-primary"
          max={Math.max(run.data.counts.total, 1)}
          value={done}
        />
        <Counts run={run.data} />
      </Card>

      <Card className="gap-0 p-0">
        {results.isPending ? (
          <Skeleton className="m-3 h-24" />
        ) : results.isError ? (
          <p className="p-4 text-sm text-destructive">{results.error.message}</p>
        ) : results.data.content.length === 0 ? (
          <p className="p-6 text-center text-sm text-muted-foreground">{active ? 'Waiting for the first result…' : 'No results.'}</p>
        ) : (
          <ul aria-label="Results" className="divide-y">
            {results.data.content.map((result) => (
              <li key={result.id}>
                <button
                  type="button"
                  className="flex w-full flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 text-left hover:bg-muted/50"
                  onClick={() => setSearchParams({ result: result.id })}
                >
                  <OutcomeBadge outcome={result.outcome} />
                  <span className="min-w-0 flex-1 truncate text-sm">{result.testName ?? result.operationKey}</span>
                  <span className="font-mono text-xs text-muted-foreground">{result.operationKey}</span>
                  {result.responseStatus !== null ? <StatusBadge status={String(result.responseStatus)} /> : null}
                  {result.errorKind ? <span className="text-xs text-destructive">{result.errorKind}</span> : null}
                  {result.durationMs !== null ? <span className="w-16 text-right text-xs text-muted-foreground">{result.durationMs} ms</span> : null}
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      <ResultSheet projectId={projectId} runId={runId} resultId={selected} onClose={() => setSearchParams({})} />
    </div>
  )
}

function ResultSheet({ projectId, runId, resultId, onClose }: { projectId: string; runId: string; resultId: string | undefined; onClose: () => void }) {
  const result = useResult(projectId, runId, resultId)
  return (
    <Sheet open={resultId !== undefined} onOpenChange={(open) => (open ? undefined : onClose())}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-2xl">
        <SheetHeader className="pr-10">
          <SheetTitle>{result.data?.testName ?? 'Result'}</SheetTitle>
          <SheetDescription className="font-mono text-xs">{result.data?.operationKey}</SheetDescription>
        </SheetHeader>
        <div className="px-4 pb-6">
          {result.isPending ? <Skeleton className="h-40" /> : result.isError ? <p className="text-destructive">{result.error.message}</p> : <ResultView result={result.data} />}
        </div>
      </SheetContent>
    </Sheet>
  )
}
