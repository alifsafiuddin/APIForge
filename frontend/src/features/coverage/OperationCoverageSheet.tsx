import { Check, Minus, X } from 'lucide-react'
import { type ReactNode, useState } from 'react'
import { Link } from 'react-router'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Skeleton } from '@/components/ui/skeleton'
import { OutcomeBadge } from '@/features/runs/ResultView'
import { MethodBadge } from '@/features/specs/components/MethodBadge'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

import { KIND_LABELS, type OperationCoverage, useOperationCoverage } from './api'
import { OutcomeBreakdown } from './OutcomeBreakdown'

function State({ ok, label }: { ok: boolean | null; label: string }) {
  const Icon = ok === null ? Minus : ok ? Check : X
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 text-xs',
        ok === null ? 'text-muted-foreground' : ok ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-700 dark:text-red-400',
      )}
    >
      <Icon className="size-3.5" aria-hidden="true" />
      {label}
    </span>
  )
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section aria-label={title} className="space-y-1.5">
      <h3 className="font-medium">{title}</h3>
      {children}
    </section>
  )
}

function Detail({ detail, projectId }: { detail: OperationCoverage; projectId: string }) {
  const [onlyMissing, setOnlyMissing] = useState(false)
  const { summary } = detail
  const scenarios = onlyMissing ? detail.scenarios.filter((scenario) => !scenario.covered) : detail.scenarios
  const names = new Map(detail.tests.map((test) => [test.id, test.name]))
  return (
    <div className="space-y-5 text-sm">
      <div className="space-y-1">
        <p className="text-xs text-muted-foreground">
          {summary.tests} distinct enabled test{summary.tests === 1 ? '' : 's'}
          {summary.duplicates ? `, ${summary.duplicates} duplicate${summary.duplicates === 1 ? '' : 's'} not counted` : ''}
          {summary.inactiveTests ? `, ${summary.inactiveTests} disabled or not approved` : ''} ·{' '}
          <Link className="underline" to={`/projects/${projectId}/tests?operation=${encodeURIComponent(summary.operationKey)}`}>
            open in Tests
          </Link>
        </p>
        {summary.tests > 0 ? <OutcomeBreakdown outcomes={summary.outcomes} /> : null}
      </div>

      <Section title="Documented responses">
        <div className="overflow-x-auto rounded-md border">
          <table className="w-full text-left text-xs">
            <thead className="bg-muted/60 text-muted-foreground">
              <tr>
                <th scope="col" className="px-2 py-1.5 font-medium">Status</th>
                <th scope="col" className="px-2 py-1.5 font-medium">Expected by a test</th>
                <th scope="col" className="px-2 py-1.5 font-medium">Received in runs</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {detail.responses.map((response) => (
                <tr key={response.status}>
                  <td className="px-2 py-1.5 font-mono font-semibold">{response.status}</td>
                  <td className="px-2 py-1.5">
                    <State ok={response.targeted} label={response.targeted ? 'yes' : 'no'} />
                  </td>
                  <td className="px-2 py-1.5">
                    <State ok={response.exercised} label={response.exercised ? response.receivedStatuses.join(', ') : 'not yet'} />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        {summary.undocumentedStatuses.length > 0 ? (
          <p className="text-xs text-amber-700 dark:text-amber-400">
            Runs also received undocumented status {summary.undocumentedStatuses.join(', ')} (reported as contract failures).
          </p>
        ) : null}
      </Section>

      <Section title="Negative scenarios">
        <ul className="grid gap-1 sm:grid-cols-2">
          {detail.negatives.map((negative) => (
            <li key={negative.kind}>
              <State
                ok={negative.state === 'NOT_APPLICABLE' ? null : negative.state === 'PRESENT'}
                label={`${negative.label}${negative.state === 'NOT_APPLICABLE' ? ' (does not apply)' : negative.state === 'MISSING' ? ' — missing' : ''}`}
              />
            </li>
          ))}
        </ul>
      </Section>

      <Section title={`Scenarios (${summary.constraintsCovered} of ${summary.constraints} constraints covered)`}>
        <div className="flex items-center gap-2">
          <p className="flex-1 text-xs text-muted-foreground">What the generator derives from this operation&apos;s contract, and whether an enabled test checks it.</p>
          <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" aria-pressed={onlyMissing} onClick={() => setOnlyMissing(!onlyMissing)}>
            {onlyMissing ? 'Show all' : 'Only missing'}
          </Button>
        </div>
        <ul aria-label="Scenarios" className="divide-y rounded-md border">
          {scenarios.length === 0 ? <li className="px-2 py-2 text-xs text-muted-foreground">Nothing to show.</li> : null}
          {scenarios.map((scenario) => (
            <li key={scenario.scenarioKey} className="flex flex-wrap items-center gap-x-2 gap-y-0.5 px-2 py-1.5">
              <State ok={scenario.covered} label={scenario.covered ? 'covered' : 'missing'} />
              <span className="min-w-0 flex-1 text-xs">{scenario.name}</span>
              <Badge variant="outline" className="text-[10px]">
                {KIND_LABELS[scenario.kind]}
              </Badge>
              {scenario.testIds.length > 1 ? <span className="text-[11px] text-muted-foreground">{scenario.testIds.length} tests</span> : null}
            </li>
          ))}
        </ul>
        {detail.skipped.length > 0 ? (
          <details className="text-xs">
            <summary className="cursor-pointer text-muted-foreground">Not generated automatically ({detail.skipped.length})</summary>
            <ul className="mt-1 space-y-1">
              {detail.skipped.map((skipped, index) => (
                <li key={index}>
                  <span className="font-medium">{skipped.scenario}</span> — {skipped.reason}
                </li>
              ))}
            </ul>
          </details>
        ) : null}
      </Section>

      {detail.branches.length > 0 ? (
        <Section title={`Schema branches (${summary.branchesExercised} of ${summary.branches} exercised)`}>
          <ul className="space-y-1">
            {detail.branches.map((branch) => (
              <li key={branch.id} className="flex flex-wrap items-center gap-x-2">
                <State ok={branch.exercised} label={branch.exercised ? 'exercised' : 'not exercised'} />
                <span className="text-xs">{branch.label}</span>
                <span className="font-mono text-[11px] text-muted-foreground">{branch.path}</span>
              </li>
            ))}
          </ul>
        </Section>
      ) : null}

      <Section title="Enabled tests">
        {detail.tests.length === 0 ? (
          <p className="text-xs text-muted-foreground">None. Generate tests or enable existing ones in the Tests tab.</p>
        ) : (
          <ul aria-label="Enabled tests" className="divide-y rounded-md border">
            {detail.tests.map((test) => (
              <li key={test.id} className="flex flex-wrap items-center gap-x-2 gap-y-0.5 px-2 py-1.5">
                {test.latestOutcome ? <OutcomeBadge outcome={test.latestOutcome} /> : <Badge variant="outline">NOT RUN</Badge>}
                <span className="min-w-0 flex-1 text-xs">
                  {test.name}
                  {test.duplicateOf ? (
                    <span className="block text-[11px] text-muted-foreground">
                      Same request as &ldquo;{names.get(test.duplicateOf) ?? 'another test'}&rdquo;; counted once.
                    </span>
                  ) : null}
                </span>
                {test.latestStatus !== null ? <span className="font-mono text-[11px]">{test.latestStatus}</span> : null}
                {test.lastExecutedAt ? <span className="text-[11px] text-muted-foreground">{formatDateTime(test.lastExecutedAt)}</span> : null}
              </li>
            ))}
          </ul>
        )}
      </Section>
    </div>
  )
}

/** Drill-down of one operation's coverage. */
export function OperationCoverageSheet({
  projectId,
  operationKey,
  environmentId,
  onClose,
}: {
  projectId: string
  operationKey: string | undefined
  environmentId: string | undefined
  onClose: () => void
}) {
  const detail = useOperationCoverage(projectId, operationKey, environmentId)
  const [method, ...path] = (operationKey ?? '').split(' ')
  return (
    <Sheet open={operationKey !== undefined} onOpenChange={(open) => (open ? undefined : onClose())}>
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-2xl">
        <SheetHeader className="pr-10">
          <SheetTitle className="flex items-center gap-2">
            {method ? <MethodBadge method={method} /> : null}
            <span className="font-mono text-sm break-all">{path.join(' ')}</span>
          </SheetTitle>
          <SheetDescription>Coverage of this operation in version {detail.data?.version.number ?? '…'}</SheetDescription>
        </SheetHeader>
        <div className="px-4 pb-6">
          {detail.isPending ? (
            <Skeleton className="h-40" />
          ) : detail.isError ? (
            <p className="text-destructive">{detail.error.message}</p>
          ) : (
            <Detail detail={detail.data} projectId={projectId} />
          )}
        </div>
      </SheetContent>
    </Sheet>
  )
}
