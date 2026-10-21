import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

import { type Snapshot, useCoverageChanges, type VersionChanges } from './api'
import { SUMMARY_LABELS } from './format'

const TRIGGERS: Record<Snapshot['trigger'], string> = {
  TESTS_GENERATED: 'Tests generated',
  RUN_FINISHED: 'Test run finished',
  VERSION_ACTIVATED: 'Version activated',
}

const KIND_STYLES = {
  ADDED: 'bg-sky-100 text-sky-800 ring-sky-200',
  CHANGED: 'bg-amber-100 text-amber-900 ring-amber-200',
  REMOVED: 'bg-muted text-muted-foreground ring-border',
}

function Delta({ before, after }: { before: number | undefined; after: number | undefined }) {
  if (before === undefined || after === undefined) return <span className="text-muted-foreground">–</span>
  const delta = after - before
  if (delta === 0) return <span className="text-muted-foreground">0</span>
  return <span className="font-medium tabular-nums">{delta > 0 ? `+${delta}` : delta}</span>
}

function MetricsComparison({ changes }: { changes: VersionChanges }) {
  const baseline = changes.baseline
  return (
    <Card className="gap-2 p-3">
      <h3 className="text-sm font-medium">Coverage then and now</h3>
      <p className="text-xs text-muted-foreground">
        {baseline
          ? `Version ${changes.from?.number}: last recorded ${formatDateTime(baseline.takenAt)} (${TRIGGERS[baseline.trigger].toLowerCase()}). Version ${changes.to.number}: now. Runs on one version do not count for another.`
          : `No coverage was recorded while version ${changes.from?.number} was active (snapshots are taken after generation, test runs and activation).`}
      </p>
      <div className="overflow-x-auto">
        <table className="w-full text-left text-xs">
          <thead className="text-muted-foreground">
            <tr>
              <th scope="col" className="py-1 pr-3 font-medium">Metric</th>
              <th scope="col" className="py-1 pr-3 text-right font-medium">Version {changes.from?.number}</th>
              <th scope="col" className="py-1 pr-3 text-right font-medium">Version {changes.to.number}</th>
              <th scope="col" className="py-1 text-right font-medium">Change</th>
            </tr>
          </thead>
          <tbody className="divide-y">
            {SUMMARY_LABELS.map(([key, label]) => (
              <tr key={key}>
                <td className="py-1 pr-3">{label}</td>
                <td className="py-1 pr-3 text-right tabular-nums">{baseline?.metrics[key] ?? '–'}</td>
                <td className="py-1 pr-3 text-right tabular-nums">{changes.current[key] ?? '–'}</td>
                <td className="py-1 text-right">
                  <Delta before={baseline?.metrics[key]} after={changes.current[key]} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </Card>
  )
}

function History({ history }: { history: Snapshot[] }) {
  return (
    <Card className="gap-2 p-3">
      <h3 className="text-sm font-medium">History</h3>
      {history.length === 0 ? (
        <p className="text-xs text-muted-foreground">Nothing recorded yet.</p>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full min-w-[34rem] text-left text-xs">
            <thead className="text-muted-foreground">
              <tr>
                <th scope="col" className="py-1 pr-3 font-medium">When</th>
                <th scope="col" className="py-1 pr-3 font-medium">Version</th>
                <th scope="col" className="py-1 pr-3 font-medium">After</th>
                <th scope="col" className="py-1 pr-3 text-right font-medium">Operations with tests</th>
                <th scope="col" className="py-1 pr-3 text-right font-medium">Tests</th>
                <th scope="col" className="py-1 text-right font-medium">Passed / executed</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {history.map((snapshot) => (
                <tr key={snapshot.id}>
                  <td className="py-1 pr-3 whitespace-nowrap">{formatDateTime(snapshot.takenAt)}</td>
                  <td className="py-1 pr-3">{snapshot.versionNumber}</td>
                  <td className="py-1 pr-3">{TRIGGERS[snapshot.trigger]}</td>
                  <td className="py-1 pr-3 text-right tabular-nums">
                    {snapshot.metrics.operationsWithTests} / {snapshot.metrics.operations}
                  </td>
                  <td className="py-1 pr-3 text-right tabular-nums">{snapshot.metrics.tests}</td>
                  <td className="py-1 text-right tabular-nums">
                    {snapshot.metrics.passed} / {snapshot.metrics.executed}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  )
}

/** What changed in the contract since the previous version, and how coverage moved. */
export function VersionChangesView({ projectId }: { projectId: string }) {
  const changes = useCoverageChanges(projectId, true)
  if (changes.isPending) return <Skeleton className="h-64" aria-label="Loading changes" />
  if (changes.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{changes.error.message}</AlertDescription>
      </Alert>
    )
  }
  const data = changes.data
  if (data.from === null) {
    return (
      <div className="space-y-3">
        <Card className="p-4 text-sm text-muted-foreground">
          Version {data.to.number} is the first version of this specification. Changes appear here after you import another one.
        </Card>
        <History history={data.history} />
      </div>
    )
  }
  return (
    <div className="space-y-3">
      <Card className="gap-2 p-3">
        <h3 className="text-sm font-medium">
          Operations changed from version {data.from.number} to {data.to.number}
        </h3>
        <p className="text-xs text-muted-foreground">
          {data.operations.length} changed, {data.unchanged} unchanged. An operation changed when its parameters, request body, responses, security or a
          schema it references differ.
        </p>
        {data.operations.length > 0 ? (
          <ul aria-label="Operation changes" className="divide-y rounded-md border">
            {data.operations.map((change) => (
              <li key={`${change.kind} ${change.operationKey}`} className="flex flex-wrap items-center gap-x-2 gap-y-1 px-2 py-1.5">
                <span className={cn('w-20 shrink-0 rounded px-1.5 py-0.5 text-center text-xs font-semibold ring-1 ring-inset', KIND_STYLES[change.kind])}>
                  {change.kind}
                </span>
                <span className="min-w-0 flex-1 font-mono text-xs break-all">{change.operationKey}</span>
                {change.aspects.map((aspect) => (
                  <Badge key={aspect} variant="outline" className="text-[10px]">
                    {aspect}
                  </Badge>
                ))}
                <span className="w-full text-xs text-muted-foreground sm:w-auto">
                  {change.kind === 'REMOVED'
                    ? `${change.staleTests} test${change.staleTests === 1 ? '' : 's'} kept as stale`
                    : change.tests === 0
                      ? 'no enabled tests yet'
                      : `${change.tests} enabled test${change.tests === 1 ? '' : 's'}${change.testsFromEarlierVersion > 0 ? `, ${change.testsFromEarlierVersion} generated from an earlier version (regenerate to update)` : ''}`}
                </span>
              </li>
            ))}
          </ul>
        ) : null}
      </Card>
      <MetricsComparison changes={data} />
      <History history={data.history} />
    </div>
  )
}
