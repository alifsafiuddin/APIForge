import { CheckCircle2, Circle, Play } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { percent, type ProjectSummary, useProjectOverview } from '@/features/dashboard/api'
import { RunRefRow } from '@/features/dashboard/components'
import { RunTestsDialog } from '@/features/runs/RunTestsDialog'
import { formatDateTime, formatRelativeTime } from '@/lib/format'
import { cn } from '@/lib/utils'

type Step = { label: string; done: boolean; to: string; hint: string }

function steps(project: ProjectSummary): Step[] {
  const base = `/projects/${project.id}`
  return [
    { label: 'Import an OpenAPI specification', done: project.activeSpecification !== null, to: `${base}/spec`, hint: 'OpenAPI 3.0 or 3.1, JSON or YAML.' },
    { label: 'Generate tests', done: project.tests.active > 0, to: `${base}/tests`, hint: 'Deterministic tests from schemas, constraints and responses.' },
    { label: 'Add an environment', done: project.environments > 0, to: `${base}/environments`, hint: 'The base URL and credentials of an API you may test.' },
    { label: 'Run the tests', done: project.lastRun !== null, to: `${base}/tests`, hint: 'Results are checked against the documented contract.' },
  ]
}

function Metric({ label, value, detail, to }: { label: string; value: string; detail: string; to: string }) {
  return (
    <Link to={to} className="rounded-xl border bg-card px-4 py-3 shadow-xs transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none">
      <span className="block text-sm text-muted-foreground">{label}</span>
      <span className="mt-1 block text-2xl font-semibold tabular-nums">{value}</span>
      <span className="mt-1 block text-xs text-muted-foreground">{detail}</span>
    </Link>
  )
}

/** The project's landing tab: what is set up, what to do next, key numbers and the latest runs. */
export function ProjectOverviewPage() {
  const { projectId = '' } = useParams()
  const overview = useProjectOverview(projectId)
  const [running, setRunning] = useState(false)

  if (overview.isPending) {
    return (
      <div className="space-y-4" aria-label="Loading project overview">
        <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
          {[0, 1, 2, 3].map((index) => (
            <Skeleton key={index} className="h-24" />
          ))}
        </div>
        <Skeleton className="h-48" />
      </div>
    )
  }
  if (overview.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription className="space-y-2">
          <p>{overview.error.message}</p>
          <Button variant="outline" size="sm" onClick={() => void overview.refetch()}>
            Try again
          </Button>
        </AlertDescription>
      </Alert>
    )
  }

  const { project, recentRuns } = overview.data
  const base = `/projects/${project.id}`
  const checklist = steps(project)
  const next = checklist.find((step) => !step.done)
  const spec = project.activeSpecification
  const coverage = project.coverage
  const canRun = project.tests.enabled > 0 && project.environments > 0 && !project.archived

  return (
    <div className="space-y-6">
      {next ? (
        <Card>
          <CardHeader>
            <CardTitle>Get this project testing</CardTitle>
            <CardDescription>
              {checklist.filter((step) => step.done).length} of {checklist.length} steps done.
            </CardDescription>
          </CardHeader>
          <CardContent>
            <ol className="space-y-2">
              {checklist.map((step) => (
                <li key={step.label} className="flex items-start gap-3 text-sm">
                  {step.done ? (
                    <CheckCircle2 className="mt-0.5 size-4 shrink-0 text-emerald-600" aria-hidden="true" />
                  ) : (
                    <Circle className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
                  )}
                  <span className="min-w-0">
                    {step === next ? (
                      <Link to={step.to} className="font-medium text-primary underline-offset-4 hover:underline">
                        {step.label}
                      </Link>
                    ) : (
                      <span className={cn(step.done && 'text-muted-foreground')}>{step.label}</span>
                    )}
                    <span className="sr-only">{step.done ? ' (done)' : ' (to do)'}</span>
                    {step === next ? <span className="block text-xs text-muted-foreground">{step.hint}</span> : null}
                  </span>
                </li>
              ))}
            </ol>
          </CardContent>
        </Card>
      ) : null}

      <section aria-label="Project numbers" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
        <Metric
          label="Operations"
          value={spec ? String(spec.operations) : '—'}
          detail={spec ? `Version ${spec.versionNumber}: ${spec.title}${spec.apiVersion ? ` ${spec.apiVersion}` : ''}` : 'No active specification'}
          to={`${base}/spec`}
        />
        <Metric
          label="Active tests"
          value={String(project.tests.active)}
          detail={`${project.tests.enabled} enabled · ${project.tests.pendingReview} to review · ${project.tests.stale} stale`}
          to={`${base}/tests`}
        />
        <Metric
          label="Operations with tests"
          value={coverage ? percent(coverage.operationsWithTests, coverage.operations) : '—'}
          detail={
            coverage
              ? `${coverage.operationsWithTests} of ${coverage.operations}; snapshot ${formatRelativeTime(coverage.takenAt)}`
              : 'Measured once tests exist'
          }
          to={`${base}/coverage`}
        />
        <Metric
          label="Environments"
          value={String(project.environments)}
          detail={project.environments === 0 ? 'Add one to run tests' : 'Targets for test runs'}
          to={`${base}/environments`}
        />
      </section>

      {coverage && coverage.executed > 0 ? (
        <p className="text-sm text-muted-foreground">
          At the snapshot of {formatDateTime(coverage.takenAt)}, {coverage.passed} of {coverage.executed} executed tests
          passed and {coverage.responsesExercised} of {coverage.responses} documented responses were observed.{' '}
          <Link className="text-foreground underline-offset-4 hover:underline" to={`${base}/coverage`}>
            Open the coverage report
          </Link>
        </p>
      ) : null}

      <Card className="gap-0 pb-0">
        <CardHeader className="pb-4">
          <CardTitle>Recent test runs</CardTitle>
          <CardDescription>
            <Link className="underline-offset-4 hover:underline" to={`${base}/runs`}>
              Full execution history
            </Link>
          </CardDescription>
          <CardAction>
            <Button size="sm" onClick={() => setRunning(true)} disabled={!canRun} title={canRun ? undefined : 'Needs enabled tests and an environment'}>
              <Play aria-hidden="true" />
              Run tests
            </Button>
          </CardAction>
        </CardHeader>
        <CardContent className="border-t px-0">
          {recentRuns.length === 0 ? (
            <p className="px-6 py-8 text-center text-sm text-muted-foreground">
              {canRun ? 'No runs yet. Start one with Run tests.' : 'Runs appear here once tests and an environment exist.'}
            </p>
          ) : (
            <ul aria-label="Recent test runs" className="divide-y">
              {recentRuns.map((run) => (
                <RunRefRow key={run.id} run={run} showProject={false} />
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {running ? (
        <RunTestsDialog projectId={project.id} open={running} onOpenChange={setRunning} operationKey={undefined} selectedTestIds={[]} />
      ) : null}
    </div>
  )
}
