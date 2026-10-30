import type { ReactNode } from 'react'
import { Link } from 'react-router'

import { Badge } from '@/components/ui/badge'
import { OutcomeBar } from '@/features/runs/RunDetailPage'
import { formatDateTime, formatRelativeTime } from '@/lib/format'
import { cn } from '@/lib/utils'

import { percent, type ProjectSummary, type RunRef, runCounts, runOutcomeText } from './api'

/** One headline number with what it counts. */
export function StatTile({
  label,
  value,
  detail,
  tone = 'default',
  to,
}: {
  label: string
  value: number
  detail: ReactNode
  tone?: 'default' | 'attention'
  to?: string | undefined
}) {
  const content = (
    <>
      <dt className="text-sm text-muted-foreground">{label}</dt>
      <dd className={cn('mt-1 text-2xl font-semibold tabular-nums', tone === 'attention' && value > 0 && 'text-amber-700 dark:text-amber-400')}>
        {value}
      </dd>
      <dd className="mt-1 text-xs text-muted-foreground">{detail}</dd>
    </>
  )
  const frame = 'block rounded-xl border bg-card px-4 py-3 shadow-xs'
  return to ? (
    <div>
      <Link to={to} className={cn(frame, 'transition-colors hover:bg-muted/50 focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none')}>
        <dl>{content}</dl>
      </Link>
    </div>
  ) : (
    <dl className={frame}>{content}</dl>
  )
}

const STATUS_VARIANTS: Record<RunRef['status'], 'secondary' | 'default' | 'destructive'> = {
  COMPLETED: 'secondary',
  QUEUED: 'default',
  RUNNING: 'default',
  CANCELLED: 'destructive',
  INTERRUPTED: 'destructive',
}

/** A run as a link to its detail page; the project name is shown when runs of several projects are listed. */
export function RunRefRow({ run, showProject }: { run: RunRef; showProject: boolean }) {
  return (
    <li>
      <Link
        to={`/projects/${run.projectId}/runs/${run.id}`}
        className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-3 text-sm hover:bg-muted/50 focus-visible:bg-muted/50 focus-visible:outline-none"
      >
        <Badge variant={STATUS_VARIANTS[run.status]}>{run.status}</Badge>
        <span className="min-w-0 flex-1 truncate">
          {showProject ? <span className="font-medium">{run.projectName}</span> : null}
          {showProject ? <span className="text-muted-foreground"> · </span> : null}
          <span className="text-muted-foreground">{run.environmentName ?? 'deleted environment'}</span>
        </span>
        <OutcomeBar counts={runCounts(run)} className="w-20" />
        <span className="w-full text-xs text-muted-foreground sm:w-auto">{runOutcomeText(run)}</span>
        <time className="text-xs text-muted-foreground" dateTime={run.createdAt} title={formatDateTime(run.createdAt)}>
          {formatRelativeTime(run.createdAt)}
        </time>
      </Link>
    </li>
  )
}

function Fact({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 py-1.5">
      <dt className="shrink-0 text-muted-foreground">{label}</dt>
      <dd className="min-w-0 truncate text-right">{children}</dd>
    </div>
  )
}

/** A project's state on the dashboard: specification, tests, environments, last run and stored coverage. */
export function ProjectSummaryCard({ project }: { project: ProjectSummary }) {
  const spec = project.activeSpecification
  const { tests, lastRun, coverage } = project
  return (
    <article className="flex flex-col rounded-xl border bg-card shadow-xs" aria-labelledby={`project-${project.id}`}>
      <div className="space-y-1 px-4 pt-4">
        <h3 id={`project-${project.id}`} className="truncate font-medium">
          <Link to={`/projects/${project.id}`} className="hover:underline focus-visible:underline focus-visible:outline-none">
            {project.name}
          </Link>
        </h3>
        <p className="line-clamp-1 min-h-5 text-sm text-muted-foreground">{project.description ?? spec?.title ?? ''}</p>
      </div>
      <dl className="flex-1 divide-y px-4 py-2 text-sm">
        <Fact label="Specification">
          {spec ? (
            `v${spec.versionNumber} · ${spec.operations} operation${spec.operations === 1 ? '' : 's'}`
          ) : (
            <Link className="text-primary underline-offset-4 hover:underline" to={`/projects/${project.id}/spec`}>
              Import one
            </Link>
          )}
        </Fact>
        <Fact label="Tests">
          {tests.active} active
          {tests.pendingReview > 0 ? <span className="text-amber-700 dark:text-amber-400"> · {tests.pendingReview} to review</span> : null}
          {tests.stale > 0 ? <span className="text-muted-foreground"> · {tests.stale} stale</span> : null}
        </Fact>
        <Fact label="Environments">{project.environments}</Fact>
        <Fact label="Operations with tests">
          {coverage ? (
            <span title={`Coverage snapshot of ${formatDateTime(coverage.takenAt)}`}>
              {coverage.operationsWithTests}/{coverage.operations} ({percent(coverage.operationsWithTests, coverage.operations)})
            </span>
          ) : (
            <span className="text-muted-foreground">not measured yet</span>
          )}
        </Fact>
      </dl>
      <div className="border-t px-4 py-3 text-sm">
        {lastRun ? (
          <Link to={`/projects/${project.id}/runs/${lastRun.id}`} className="block space-y-1.5 hover:underline focus-visible:underline focus-visible:outline-none">
            <span className="flex items-center justify-between gap-2 text-xs text-muted-foreground">
              <span>Last run {formatRelativeTime(lastRun.createdAt)}</span>
              <Badge variant={STATUS_VARIANTS[lastRun.status]}>{lastRun.status}</Badge>
            </span>
            <OutcomeBar counts={runCounts(lastRun)} />
            <span className="block text-xs">{runOutcomeText(lastRun)}</span>
          </Link>
        ) : (
          <p className="text-xs text-muted-foreground">No test runs yet.</p>
        )}
      </div>
    </article>
  )
}
