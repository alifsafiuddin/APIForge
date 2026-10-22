import { FlaskConical, FolderCog, ListChecks, Play, Search, Sparkles } from 'lucide-react'
import { useCallback, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { useProject } from '@/features/projects/api'
import { type OperationSummary, useOperations } from '@/features/specs/api'
import { MethodBadge, StatusBadge } from '@/features/specs/components/MethodBadge'
import { matchesQuery } from '@/features/specs/operation-filter'
import { AiSuggestionsPanel } from '@/features/ai/AiSuggestionsPanel'
import { RunTestsDialog } from '@/features/runs/RunTestsDialog'
import { cn } from '@/lib/utils'

import {
  type Category,
  CATEGORIES,
  CATEGORY_LABELS,
  type GenerationReport,
  type TestSummary,
  useBulkChange,
  useChangeTest,
  useGenerateTests,
  useGenerationPlan,
  useSuites,
  useTestCounts,
  useTests,
} from './api'
import { AllTestsPanel } from './AllTestsPanel'
import { TEST_VIEWS, type TestView } from './views'
import { SuitesDialog } from './SuitesDialog'
import { TestDetailSheet } from './TestDetailSheet'

const SELECT_CLASS =
  'h-8 max-w-full min-w-0 rounded-lg border border-input bg-background px-2.5 text-sm focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none'

/**
 * The test workspace of a project: generate deterministic tests from the active specification,
 * then review, edit, enable/disable and organize them per endpoint.
 */
export function TestsPage() {
  const { projectId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const project = useProject(projectId)
  const activeVersionId = project.data?.activeSpecificationVersionId ?? undefined
  const operations = useOperations(projectId, activeVersionId)
  const counts = useTestCounts(projectId)
  const generate = useGenerateTests(projectId)
  const [report, setReport] = useState<GenerationReport | null>(null)
  const [suitesOpen, setSuitesOpen] = useState(false)

  const selectedKey = searchParams.get('operation') ?? undefined
  const selectedTest = searchParams.get('test') ?? undefined
  const viewParam = searchParams.get('view')
  const view: TestView = viewParam !== null && viewParam in TEST_VIEWS ? (viewParam as TestView) : 'all'
  const findQuery = searchParams.get('find') ?? ''
  const findPage = Math.max(0, Number(searchParams.get('tpage')) || 0)

  const updateParams = useCallback(
    (changes: Record<string, string | undefined>) => {
      setSearchParams((current) => {
        const next = new URLSearchParams(current)
        for (const [key, value] of Object.entries(changes)) {
          if (value === undefined || value === '') next.delete(key)
          else next.set(key, value)
        }
        return next
      })
    },
    [setSearchParams],
  )
  const updateFinder = useCallback(
    (changes: { view?: TestView; query?: string; page?: number }) =>
      updateParams({
        ...(changes.view !== undefined ? { view: changes.view === 'all' ? undefined : changes.view } : {}),
        ...(changes.query !== undefined ? { find: changes.query } : {}),
        ...(changes.page !== undefined ? { tpage: changes.page > 0 ? String(changes.page) : undefined } : {}),
      }),
    [updateParams],
  )

  const runGeneration = async () => {
    try {
      const result = await generate.mutateAsync(undefined)
      setReport(result)
      toast.success(
        `Generated ${result.total} tests from version ${result.specificationVersionNumber}: ${result.created} new, ${result.updated} updated, ${result.unchanged} unchanged.`,
      )
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'Tests could not be generated.')
    }
  }

  if (project.isPending || counts.isPending) {
    return (
      <div className="grid gap-4 lg:grid-cols-[22rem_1fr]" aria-label="Loading tests">
        <Skeleton className="h-96" />
        <Skeleton className="h-96" />
      </div>
    )
  }
  if (project.isError || counts.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{(project.error ?? counts.error)?.message}</AlertDescription>
      </Alert>
    )
  }

  if (!activeVersionId) {
    return (
      <Card className="items-center gap-3 px-6 py-12 text-center">
        <FlaskConical className="size-8 text-muted-foreground" aria-hidden="true" />
        <h2 className="font-medium">Import a specification first</h2>
        <p className="max-w-md text-sm text-muted-foreground">
          Tests are generated from the active OpenAPI specification of this project.
        </p>
        <Button asChild className="mt-2">
          <Link to={`/projects/${projectId}/spec`}>Go to Specification</Link>
        </Button>
      </Card>
    )
  }

  const generateButton = (
    <Button onClick={() => void runGeneration()} disabled={generate.isPending}>
      <Sparkles aria-hidden="true" />
      {generate.isPending ? 'Generating…' : counts.data.total > 0 ? 'Regenerate tests' : 'Generate tests'}
    </Button>
  )

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm text-muted-foreground">
          {counts.data.total > 0
            ? `${counts.data.total} tests, ${counts.data.enabled} enabled. Generated from the active specification without AI; regenerating never duplicates tests or overwrites your edits.`
            : 'Deterministic tests are derived from schemas, constraints, responses and security requirements.'}
        </p>
        <div className="flex shrink-0 gap-2">
          <Button variant="outline" onClick={() => setSuitesOpen(true)}>
            <FolderCog aria-hidden="true" />
            Suites
          </Button>
          {generateButton}
        </div>
      </div>

      {report ? <ReportAlert report={report} onDismiss={() => setReport(null)} /> : null}

      {counts.data.total === 0 ? (
        <Card className="items-center gap-3 px-6 py-12 text-center">
          <ListChecks className="size-8 text-muted-foreground" aria-hidden="true" />
          <h2 className="font-medium">No tests yet</h2>
          <p className="max-w-md text-sm text-muted-foreground">
            Generate valid requests, missing and wrongly typed fields, enum, boundary and length violations, content-type
            and authentication checks, each with an explicit expected outcome and the reason it exists.
          </p>
          <div className="mt-2">{generateButton}</div>
        </Card>
      ) : (
        <div className="grid gap-4 lg:grid-cols-[22rem_minmax(0,1fr)]">
          <Card className={cn('h-fit p-3 lg:sticky lg:top-20', selectedKey && 'hidden lg:block')}>
            {operations.isPending ? (
              <Skeleton className="h-64 w-full" aria-label="Loading endpoints" />
            ) : operations.isError ? (
              <p className="text-sm text-destructive">{operations.error.message}</p>
            ) : (
              <EndpointIndex
                operations={operations.data}
                counts={new Map(counts.data.operations.map((count) => [count.operationKey, count]))}
                selectedKey={selectedKey}
                onSelect={(operationKey) => updateParams({ operation: operationKey, test: undefined })}
              />
            )}
          </Card>
          <div className="min-w-0">
            {selectedKey ? (
              <OperationTests
                projectId={projectId}
                operationKey={selectedKey}
                onBack={() => updateParams({ operation: undefined, test: undefined })}
                onOpen={(testId) => updateParams({ test: testId })}
              />
            ) : (
              <AllTestsPanel
                projectId={projectId}
                view={view}
                query={findQuery}
                page={findPage}
                onChange={updateFinder}
                onOpen={(testId) => updateParams({ test: testId })}
              />
            )}
          </div>
        </div>
      )}

      <TestDetailSheet
        projectId={projectId}
        testId={selectedTest}
        onClose={() => updateParams({ test: undefined })}
      />
      <SuitesDialog projectId={projectId} open={suitesOpen} onOpenChange={setSuitesOpen} />
    </div>
  )
}

function ReportAlert({ report, onDismiss }: { report: GenerationReport; onDismiss: () => void }) {
  return (
    <Alert>
      <Sparkles aria-hidden="true" />
      <AlertTitle>
        Generated from version {report.specificationVersionNumber} ({report.operations} endpoints, generator v
        {report.generatorVersion})
      </AlertTitle>
      <AlertDescription className="space-y-2">
        <p>
          {report.total} tests: {report.created} new, {report.updated} updated, {report.unchanged} unchanged
          {report.keptUserEdits > 0 ? `, ${report.keptUserEdits} edited by you and left as they are` : ''}
          {report.archived > 0 ? `, ${report.archived} archived because their scenario no longer applies` : ''}.
        </p>
        {report.skipped.length > 0 ? (
          <details>
            <summary className="cursor-pointer">{report.skipped.length} scenarios were not generated (with reasons)</summary>
            <ul className="mt-2 max-h-64 space-y-1 overflow-y-auto pl-4 text-xs">
              {report.skipped.map((skip, index) => (
                <li key={index} className="list-disc">
                  <span className="font-mono">{skip.operationKey}</span> — {skip.scenario}: {skip.reason}
                </li>
              ))}
            </ul>
          </details>
        ) : null}
        <Button variant="outline" size="sm" onClick={onDismiss}>
          Dismiss
        </Button>
      </AlertDescription>
    </Alert>
  )
}

type Count = { total: number; enabled: number; stale: number }

function EndpointIndex({
  operations,
  counts,
  selectedKey,
  onSelect,
}: {
  operations: OperationSummary[]
  counts: Map<string, Count>
  selectedKey: string | undefined
  onSelect: (operationKey: string) => void
}) {
  const [query, setQuery] = useState('')
  const filtered = operations.filter((operation) => matchesQuery(operation, query))
  return (
    <div className="space-y-3">
      <div className="relative">
        <Search
          className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden="true"
        />
        <Input
          type="search"
          placeholder="Search endpoints"
          aria-label="Search endpoints"
          className="pl-8"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>
      <nav aria-label="Endpoints with tests">
        <ul className="space-y-0.5">
          {filtered.map((operation) => {
            const count = counts.get(operation.operationKey)
            const selected = operation.operationKey === selectedKey
            return (
              <li key={operation.id}>
                <button
                  type="button"
                  aria-current={selected ? 'true' : undefined}
                  onClick={() => onSelect(operation.operationKey)}
                  className={cn(
                    'flex w-full min-w-0 items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                    selected && 'bg-muted font-medium',
                  )}
                >
                  <MethodBadge method={operation.method} />
                  <span className="min-w-0 flex-1 truncate font-mono text-xs">{operation.path}</span>
                  <span className="shrink-0 text-xs text-muted-foreground" aria-label={`${count?.total ?? 0} tests`}>
                    {count ? `${count.enabled}/${count.total}` : '0'}
                  </span>
                </button>
              </li>
            )
          })}
        </ul>
        {filtered.length === 0 ? (
          <p className="py-6 text-center text-sm text-muted-foreground">No endpoints match your search.</p>
        ) : null}
      </nav>
      <p className="text-xs text-muted-foreground">Counts show enabled / total tests.</p>
    </div>
  )
}

function OperationTests({
  projectId,
  operationKey,
  onBack,
  onOpen,
}: {
  projectId: string
  operationKey: string
  onBack: () => void
  onOpen: (testId: string) => void
}) {
  const [category, setCategory] = useState<Category | ''>('')
  const [state, setState] = useState<'' | 'enabled' | 'disabled'>('')
  const [suiteId, setSuiteId] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [running, setRunning] = useState(false)
  const suites = useSuites(projectId)
  const tests = useTests(projectId, {
    operationKey,
    category: category || undefined,
    enabled: state === '' ? undefined : state === 'enabled',
    suiteId: suiteId || undefined,
  })
  const plan = useGenerationPlan(projectId, operationKey, true)
  const change = useChangeTest(projectId)
  const bulk = useBulkChange(projectId)
  const [method = '', path = ''] = splitKey(operationKey)

  const rows = useMemo(() => tests.data?.content ?? [], [tests.data])
  const visibleSelected = selected.filter((id) => rows.some((row) => row.id === id))

  const toggleOne = async (test: TestSummary) => {
    try {
      await change.mutateAsync({ testId: test.id, enabled: !test.enabled, version: test.version })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The test could not be changed.')
    }
  }

  const applyBulk = async (change: { enabled?: boolean; suiteId?: string }) => {
    try {
      const result = await bulk.mutateAsync({ testIds: visibleSelected, ...change })
      toast.success(`${result.updated} test${result.updated === 1 ? '' : 's'} updated.`)
      setSelected([])
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The tests could not be changed.')
    }
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <Button variant="ghost" size="sm" className="lg:hidden" onClick={onBack}>
          ← Endpoints
        </Button>
        <MethodBadge method={method} />
        <h2 className="min-w-0 flex-1 truncate font-mono text-sm font-medium">{path}</h2>
        <Button size="sm" onClick={() => setRunning(true)}>
          <Play aria-hidden="true" />
          Run
        </Button>
      </div>
      <AiSuggestionsPanel key={operationKey} projectId={projectId} operationKey={operationKey} onOpen={onOpen} />
      {running ? (
        <RunTestsDialog
          projectId={projectId}
          open={running}
          onOpenChange={setRunning}
          operationKey={operationKey}
          selectedTestIds={visibleSelected}
        />
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        <select
          aria-label="Filter by category"
          className={SELECT_CLASS}
          value={category}
          onChange={(event) => setCategory(event.target.value as Category | '')}
        >
          <option value="">All categories</option>
          {CATEGORIES.map((value) => (
            <option key={value} value={value}>
              {CATEGORY_LABELS[value]}
            </option>
          ))}
        </select>
        <select
          aria-label="Filter by state"
          className={SELECT_CLASS}
          value={state}
          onChange={(event) => setState(event.target.value as '' | 'enabled' | 'disabled')}
        >
          <option value="">Enabled and disabled</option>
          <option value="enabled">Enabled only</option>
          <option value="disabled">Disabled only</option>
        </select>
        <select
          aria-label="Filter by suite"
          className={SELECT_CLASS}
          value={suiteId}
          onChange={(event) => setSuiteId(event.target.value)}
        >
          <option value="">All suites</option>
          {suites.data?.map((suite) => (
            <option key={suite.id} value={suite.id}>
              {suite.name}
            </option>
          ))}
        </select>
      </div>

      {visibleSelected.length > 0 ? (
        <div
          role="toolbar"
          aria-label="Selected tests"
          className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/50 px-3 py-2 text-sm"
        >
          <span>{visibleSelected.length} selected</span>
          <Button size="sm" variant="outline" disabled={bulk.isPending} onClick={() => void applyBulk({ enabled: true })}>
            Enable
          </Button>
          <Button size="sm" variant="outline" disabled={bulk.isPending} onClick={() => void applyBulk({ enabled: false })}>
            Disable
          </Button>
          <select
            aria-label="Move selected tests to suite"
            className={SELECT_CLASS}
            value=""
            disabled={bulk.isPending}
            onChange={(event) => {
              if (event.target.value) void applyBulk({ suiteId: event.target.value })
            }}
          >
            <option value="">Move to suite…</option>
            {suites.data?.map((suite) => (
              <option key={suite.id} value={suite.id}>
                {suite.name}
              </option>
            ))}
          </select>
          <Button size="sm" variant="ghost" onClick={() => setSelected([])}>
            Clear
          </Button>
        </div>
      ) : null}

      <Card className="gap-0 p-0">
        {tests.isPending ? (
          <div className="space-y-2 p-3" aria-label="Loading tests">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : tests.isError ? (
          <p className="p-4 text-sm text-destructive">{tests.error.message}</p>
        ) : rows.length === 0 ? (
          <p className="p-6 text-center text-sm text-muted-foreground">No tests match these filters.</p>
        ) : (
          <ul aria-label="Tests" className="divide-y">
            <li className="flex items-center gap-3 px-3 py-2 text-xs text-muted-foreground">
              <input
                type="checkbox"
                aria-label="Select all tests"
                className="size-4"
                checked={visibleSelected.length === rows.length}
                onChange={(event) => setSelected(event.target.checked ? rows.map((row) => row.id) : [])}
              />
              <span className="flex-1">
                {rows.length} test{rows.length === 1 ? '' : 's'}
                {tests.data.page.totalElements > rows.length ? ` (first ${rows.length} of ${tests.data.page.totalElements})` : ''}
              </span>
              <span>Enabled</span>
            </li>
            {rows.map((test) => (
              <li key={test.id} className={cn('flex items-center gap-3 px-3 py-2', !test.enabled && 'bg-muted/40')}>
                <input
                  type="checkbox"
                  aria-label={`Select ${test.name}`}
                  className="size-4"
                  checked={selected.includes(test.id)}
                  onChange={(event) =>
                    setSelected((current) =>
                      event.target.checked ? [...current, test.id] : current.filter((id) => id !== test.id),
                    )
                  }
                />
                <button
                  type="button"
                  onClick={() => onOpen(test.id)}
                  className="min-w-0 flex-1 text-left focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
                >
                  <span className={cn('block truncate text-sm', !test.enabled && 'text-muted-foreground line-through')}>
                    {test.name}
                  </span>
                  <span className="mt-1 flex flex-wrap items-center gap-1.5">
                    <Badge variant="secondary">{CATEGORY_LABELS[test.category]}</Badge>
                    {test.expectedStatuses.map((status) => (
                      <StatusBadge key={status} status={status} />
                    ))}
                    {test.userModified ? <Badge variant="outline">Edited</Badge> : null}
                    {test.reviewStatus !== 'APPROVED' ? (
                      <Badge variant="destructive">{test.reviewStatus === 'REJECTED' ? 'Rejected' : 'Needs review'}</Badge>
                    ) : null}
                    {test.lifecycleStatus === 'STALE' ? <Badge variant="outline">Stale</Badge> : null}
                    {test.origin === 'AI_SUGGESTED' ? <Badge variant="outline">AI-suggested</Badge> : null}
                  </span>
                </button>
                <input
                  type="checkbox"
                  role="switch"
                  aria-label={`Enabled: ${test.name}`}
                  aria-checked={test.enabled}
                  className="size-4"
                  checked={test.enabled}
                  disabled={change.isPending}
                  onChange={() => void toggleOne(test)}
                />
              </li>
            ))}
          </ul>
        )}
      </Card>

      {plan.data && plan.data.skipped.length > 0 ? (
        <details className="rounded-lg border px-3 py-2 text-sm">
          <summary className="cursor-pointer">Not generated for this endpoint ({plan.data.skipped.length})</summary>
          <ul className="mt-2 space-y-1 pl-4 text-muted-foreground">
            {plan.data.skipped.map((skip, index) => (
              <li key={index} className="list-disc">
                <span className="text-foreground">{skip.scenario}</span>: {skip.reason}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  )
}

function splitKey(operationKey: string): [string, string] {
  const space = operationKey.indexOf(' ')
  return space < 0 ? [operationKey, ''] : [operationKey.slice(0, space), operationKey.slice(space + 1)]
}
