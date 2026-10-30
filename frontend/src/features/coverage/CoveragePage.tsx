import { Info, Search } from 'lucide-react'
import { useMemo } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'

import { ApiError } from '@/api/client'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useEnvironments } from '@/features/environments/api'
import { MethodBadge } from '@/features/specs/components/MethodBadge'
import { cn } from '@/lib/utils'

import { type CoverageReport, KIND_LABELS, type Metric, type OperationRow, useCoverage } from './api'
import { percent } from './format'
import { OperationCoverageSheet } from './OperationCoverageSheet'
import { OutcomeBreakdown } from './OutcomeBreakdown'
import { VersionChangesView } from './VersionChangesView'

const SELECT_CLASS =
  'h-8 max-w-full min-w-0 rounded-lg border border-input bg-background px-2.5 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none'

const GAP_FILTERS = {
  ALL: 'All operations',
  NO_TESTS: 'Without tests',
  RESPONSES: 'Responses not exercised',
  NEGATIVES: 'Missing negative scenarios',
  CONSTRAINTS: 'Constraints not covered',
  FAILING: 'Failed or errored tests',
  UNDOCUMENTED: 'Undocumented statuses received',
} as const
type GapFilter = keyof typeof GAP_FILTERS

function matchesGap(row: OperationRow, gap: GapFilter) {
  switch (gap) {
    case 'ALL':
      return true
    case 'NO_TESTS':
      return row.tests === 0
    case 'RESPONSES':
      return row.responsesExercised < row.responses
    case 'NEGATIVES':
      return row.missingNegatives.length > 0
    case 'CONSTRAINTS':
      return row.constraintsCovered < row.constraints
    case 'FAILING':
      return row.outcomes.failed + row.outcomes.error > 0
    case 'UNDOCUMENTED':
      return row.undocumentedStatuses.length > 0
  }
}

function Bar({ value, total, className }: { value: number; total: number; className?: string }) {
  return (
    <div className="h-1.5 w-full overflow-hidden rounded bg-muted" aria-hidden="true">
      <div className={cn('h-full bg-primary', className)} style={{ width: `${total === 0 ? 0 : (value / total) * 100}%` }} />
    </div>
  )
}

function MetricCard({ metric }: { metric: Metric }) {
  return (
    <Card className="gap-1.5 p-3">
      <h3 className="text-sm text-muted-foreground">{metric.label}</h3>
      <p className="text-xl font-semibold tabular-nums">
        {metric.total === null ? (
          metric.value
        ) : (
          <>
            {metric.value} <span className="text-sm font-normal text-muted-foreground">of {metric.total}</span>{' '}
            <span className="text-sm font-medium">{percent(metric.value, metric.total)}</span>
          </>
        )}
      </p>
      {metric.total !== null ? <Bar value={metric.value} total={metric.total} /> : null}
      <p className="text-xs text-muted-foreground">{metric.definition}</p>
    </Card>
  )
}

function Gaps({ report, open }: { report: CoverageReport; open: (key: string) => void }) {
  const { gaps } = report
  const OperationLink = ({ operationKey }: { operationKey: string }) => (
    <button type="button" className="text-left font-mono text-xs break-all text-primary hover:underline" onClick={() => open(operationKey)}>
      {operationKey}
    </button>
  )
  return (
    <section aria-label="Gaps" className="grid gap-3 lg:grid-cols-3">
      <Card className="gap-2 p-3">
        <h3 className="text-sm font-medium">Endpoints without tests ({gaps.operationsWithoutTests.length})</h3>
        {gaps.operationsWithoutTests.length === 0 ? (
          <p className="text-xs text-muted-foreground">Every operation has at least one enabled test.</p>
        ) : (
          <ul className="space-y-1">
            {gaps.operationsWithoutTests.map((key) => (
              <li key={key}>
                <OperationLink operationKey={key} />
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card className="gap-2 p-3">
        <h3 className="text-sm font-medium">Documented responses not exercised ({gaps.responsesNotExercised.length})</h3>
        {gaps.responsesNotExercised.length === 0 ? (
          <p className="text-xs text-muted-foreground">Test runs on this version received every documented response.</p>
        ) : (
          <ul className="max-h-64 space-y-1 overflow-y-auto">
            {gaps.responsesNotExercised.map((gap) => (
              <li key={`${gap.operationKey} ${gap.status}`} className="flex flex-wrap items-baseline gap-x-2">
                <span className="font-mono text-xs font-semibold">{gap.status}</span>
                <OperationLink operationKey={gap.operationKey} />
                <span className="text-xs text-muted-foreground">{gap.targeted ? 'a test expects it' : 'no test expects it'}</span>
              </li>
            ))}
          </ul>
        )}
      </Card>
      <Card className="gap-2 p-3">
        <h3 className="text-sm font-medium">Missing negative scenarios</h3>
        {gaps.missingNegatives.length === 0 ? (
          <p className="text-xs text-muted-foreground">Every applicable kind of invalid request has an enabled test.</p>
        ) : (
          <ul className="space-y-2">
            {gaps.missingNegatives.map((gap) => (
              <li key={gap.kind}>
                <p className="text-xs font-medium">
                  {gap.label} — {gap.operations.length} operation{gap.operations.length === 1 ? '' : 's'}
                </p>
                <ul className="ml-2 space-y-0.5">
                  {gap.operations.map((key) => (
                    <li key={key}>
                      <OperationLink operationKey={key} />
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </section>
  )
}

function Ratio({ value, total }: { value: number; total: number }) {
  if (total === 0) return <span className="text-muted-foreground">–</span>
  return (
    <span className={cn('tabular-nums', value < total && 'text-amber-700 dark:text-amber-400')}>
      {value}/{total}
    </span>
  )
}

/** Specification coverage of the active version: definitions, metrics, gaps and per-operation drill-down. */
export function CoveragePage() {
  const { projectId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const environmentId = searchParams.get('env') ?? undefined
  const view = searchParams.get('view') === 'changes' ? 'changes' : 'overview'
  const gapParam = searchParams.get('gap')
  const gap: GapFilter = gapParam && gapParam in GAP_FILTERS ? (gapParam as GapFilter) : 'ALL'
  const q = searchParams.get('q') ?? ''
  const method = searchParams.get('method') ?? ''
  const tag = searchParams.get('tag') ?? ''
  const selected = searchParams.get('operation') ?? undefined
  const coverage = useCoverage(projectId, environmentId)
  const environments = useEnvironments(projectId)

  const update = (changes: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(changes)) {
      if (value === undefined || value === '') next.delete(key)
      else next.set(key, value)
    }
    setSearchParams(next)
  }

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (coverage.data?.operations ?? []).filter(
      (row) =>
        matchesGap(row, gap) &&
        (method === '' || row.method === method) &&
        (tag === '' || row.tags.includes(tag)) &&
        (needle === '' || row.operationKey.toLowerCase().includes(needle)),
    )
  }, [coverage.data, gap, method, tag, q])

  if (coverage.isPending) return <Skeleton className="h-96" aria-label="Loading coverage" />
  if (coverage.isError) {
    if (coverage.error instanceof ApiError && coverage.error.code === 'NO_ACTIVE_SPECIFICATION') {
      return (
        <Card className="items-center gap-2 px-6 py-12 text-center">
          <h2 className="font-medium">No specification yet</h2>
          <p className="max-w-md text-sm text-muted-foreground">
            Coverage is measured against the active specification.{' '}
            <Link className="underline" to={`/projects/${projectId}/spec`}>
              Import one
            </Link>{' '}
            first.
          </p>
        </Card>
      )
    }
    return (
      <Alert variant="destructive">
        <AlertDescription>{coverage.error.message}</AlertDescription>
      </Alert>
    )
  }
  const report = coverage.data
  const design = report.metrics.filter((metric) => metric.group === 'TEST_DESIGN')
  const execution = report.metrics.filter((metric) => metric.group === 'EXECUTION')
  const methods = [...new Set(report.operations.map((row) => row.method))]
  const tags = [...new Set(report.operations.flatMap((row) => row.tags))].sort()
  const environmentName = environments.data?.find((environment) => environment.id === environmentId)?.name

  return (
    <div className="space-y-5">
      <Alert>
        <Info className="size-4" aria-hidden="true" />
        <AlertDescription>
          Specification coverage of version {report.version.number}
          {report.version.title ? ` (${report.version.title}${report.version.apiVersion ? ` ${report.version.apiVersion}` : ''})` : ''}: how much of the
          documented contract your tests and runs exercise. APIForge cannot see the target API&apos;s source code, so this is not code coverage.
        </AlertDescription>
      </Alert>

      <div className="flex flex-wrap items-center gap-2">
        <fieldset className="flex gap-1">
          <legend className="sr-only">View</legend>
          <Button size="sm" variant={view === 'overview' ? 'default' : 'outline'} aria-pressed={view === 'overview'} onClick={() => update({ view: undefined })}>
            Overview
          </Button>
          <Button size="sm" variant={view === 'changes' ? 'default' : 'outline'} aria-pressed={view === 'changes'} onClick={() => update({ view: 'changes' })}>
            Version changes
          </Button>
        </fieldset>
        <span className="flex-1" />
        {view === 'overview' ? (
          <select
            aria-label="Runs to include"
            className={SELECT_CLASS}
            value={environmentId ?? ''}
            onChange={(event) => update({ env: event.target.value || undefined })}
          >
            <option value="">Runs in all environments</option>
            {(environments.data ?? []).map((environment) => (
              <option key={environment.id} value={environment.id}>
                Runs in {environment.name}
              </option>
            ))}
          </select>
        ) : null}
      </div>

      {view === 'changes' ? (
        <VersionChangesView projectId={projectId} />
      ) : (
        <>
          <section aria-labelledby="design-heading" className="space-y-2">
            <div>
              <h2 id="design-heading" className="font-medium">
                Test design
              </h2>
              <p className="text-sm text-muted-foreground">What the enabled tests cover, whether or not they have run.</p>
            </div>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              {design.map((metric) => (
                <MetricCard key={metric.id} metric={metric} />
              ))}
            </div>
          </section>

          <section aria-labelledby="execution-heading" className="space-y-2">
            <div>
              <h2 id="execution-heading" className="font-medium">
                Execution
              </h2>
              <p className="text-sm text-muted-foreground">
                Latest results of test runs on version {report.version.number}
                {environmentName ? ` in ${environmentName}` : ', all environments'}. A pass rate says how the API behaved, not how much is covered.
              </p>
            </div>
            <Card className="gap-2 p-3">
              <h3 className="text-sm text-muted-foreground">Latest outcome of {report.outcomes.tests} distinct enabled tests</h3>
              <OutcomeBreakdown outcomes={report.outcomes} />
            </Card>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
              {execution.map((metric) => (
                <MetricCard key={metric.id} metric={metric} />
              ))}
            </div>
          </section>

          <Gaps report={report} open={(key) => update({ operation: key })} />

          <section aria-labelledby="operations-heading" className="space-y-2">
            <h2 id="operations-heading" className="font-medium">
              Operations
            </h2>
            <div className="flex flex-wrap items-center gap-2">
              <div className="relative min-w-0 flex-1 sm:max-w-xs">
                <Search className="pointer-events-none absolute top-2 left-2 size-4 text-muted-foreground" aria-hidden="true" />
                <Input aria-label="Search operations" className="h-8 pl-8" placeholder="Search path" value={q} onChange={(event) => update({ q: event.target.value })} />
              </div>
              <select aria-label="Filter by gap" className={SELECT_CLASS} value={gap} onChange={(event) => update({ gap: event.target.value === 'ALL' ? undefined : event.target.value })}>
                {Object.entries(GAP_FILTERS).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
              <select aria-label="Filter by method" className={SELECT_CLASS} value={method} onChange={(event) => update({ method: event.target.value })}>
                <option value="">All methods</option>
                {methods.map((value) => (
                  <option key={value}>{value}</option>
                ))}
              </select>
              {tags.length > 0 ? (
                <select aria-label="Filter by tag" className={SELECT_CLASS} value={tag} onChange={(event) => update({ tag: event.target.value })}>
                  <option value="">All tags</option>
                  {tags.map((value) => (
                    <option key={value}>{value}</option>
                  ))}
                </select>
              ) : null}
              <span className="text-xs text-muted-foreground">
                {rows.length} of {report.operations.length}
              </span>
            </div>
            <Card className="gap-0 overflow-x-auto p-0">
              <table className="w-full min-w-[46rem] text-left text-sm">
                <thead className="border-b bg-muted/50 text-xs text-muted-foreground">
                  <tr>
                    <th scope="col" className="px-3 py-2 font-medium">Operation</th>
                    <th scope="col" className="px-3 py-2 font-medium">Tests</th>
                    <th scope="col" className="px-3 py-2 font-medium">Responses targeted</th>
                    <th scope="col" className="px-3 py-2 font-medium">Responses exercised</th>
                    <th scope="col" className="px-3 py-2 font-medium">Constraints</th>
                    <th scope="col" className="px-3 py-2 font-medium">Branches</th>
                    <th scope="col" className="px-3 py-2 font-medium">Missing negatives</th>
                    <th scope="col" className="w-36 px-3 py-2 font-medium">Latest results</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {rows.length === 0 ? (
                    <tr>
                      <td colSpan={8} className="px-3 py-6 text-center text-muted-foreground">
                        No operation matches these filters.
                      </td>
                    </tr>
                  ) : (
                    rows.map((row) => (
                      <tr key={row.operationKey} className="align-top hover:bg-muted/40">
                        <td className="px-3 py-2">
                          <button type="button" className="flex items-start gap-2 text-left hover:underline" onClick={() => update({ operation: row.operationKey })}>
                            <MethodBadge method={row.method} />
                            <span className={cn('font-mono text-xs break-all', row.deprecated && 'line-through')}>{row.path}</span>
                          </button>
                          {row.undocumentedStatuses.length > 0 ? (
                            <p className="mt-0.5 text-xs text-amber-700 dark:text-amber-400">Undocumented: {row.undocumentedStatuses.join(', ')}</p>
                          ) : null}
                        </td>
                        <td className="px-3 py-2 tabular-nums">
                          {row.tests === 0 ? <span className="font-medium text-red-700 dark:text-red-400">none</span> : row.tests}
                          {row.duplicates > 0 ? <span className="block text-xs text-muted-foreground">+{row.duplicates} duplicate</span> : null}
                          {row.inactiveTests > 0 ? <span className="block text-xs text-muted-foreground">{row.inactiveTests} disabled</span> : null}
                        </td>
                        <td className="px-3 py-2">
                          <Ratio value={row.responsesTargeted} total={row.responses} />
                        </td>
                        <td className="px-3 py-2">
                          <Ratio value={row.responsesExercised} total={row.responses} />
                        </td>
                        <td className="px-3 py-2">
                          <Ratio value={row.constraintsCovered} total={row.constraints} />
                        </td>
                        <td className="px-3 py-2">
                          <Ratio value={row.branchesExercised} total={row.branches} />
                        </td>
                        <td className="px-3 py-2 text-xs">
                          {row.missingNegatives.length === 0 ? (
                            <span className="text-muted-foreground">–</span>
                          ) : (
                            <span className="text-amber-700 dark:text-amber-400">{row.missingNegatives.map((kind) => KIND_LABELS[kind]).join(', ')}</span>
                          )}
                        </td>
                        <td className="px-3 py-2">
                          {row.tests === 0 ? (
                            <span className="text-xs text-muted-foreground">–</span>
                          ) : (
                            <>
                              <OutcomeBreakdown outcomes={row.outcomes} compact />
                              <span className="text-xs text-muted-foreground tabular-nums">
                                {row.outcomes.passed}/{row.outcomes.executed} passed
                                {row.outcomes.executed < row.outcomes.tests ? `, ${row.outcomes.tests - row.outcomes.executed} not executed` : ''}
                              </span>
                            </>
                          )}
                        </td>
                      </tr>
                    ))
                  )}
                </tbody>
              </table>
            </Card>
          </section>
        </>
      )}

      <OperationCoverageSheet projectId={projectId} operationKey={selected} environmentId={environmentId} onClose={() => update({ operation: undefined })} />
    </div>
  )
}
