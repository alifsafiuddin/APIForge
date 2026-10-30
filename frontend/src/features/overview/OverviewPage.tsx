import { ArrowRight, FolderPlus, History } from 'lucide-react'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'

import { PageHeader } from '@/components/PageHeader'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { useCurrentUser } from '@/features/auth/api'
import { useDashboard } from '@/features/dashboard/api'
import { ProjectSummaryCard, RunRefRow, StatTile } from '@/features/dashboard/components'
import { ProjectFormDialog } from '@/features/projects/ProjectFormDialog'
import { SystemStatusCard } from '@/features/system/SystemStatusCard'

/** Home page: totals, the most recently active projects and the latest runs, all counted by the backend. */
export function OverviewPage() {
  const user = useCurrentUser()
  const dashboard = useDashboard()
  const navigate = useNavigate()
  const [creating, setCreating] = useState(false)

  return (
    <div className="space-y-8">
      <PageHeader
        title="Overview"
        description={
          user.data ? `Welcome back, ${user.data.displayName}.` : 'Contract-driven API testing from your OpenAPI specifications.'
        }
        actions={
          <Button onClick={() => setCreating(true)}>
            <FolderPlus aria-hidden="true" />
            New project
          </Button>
        }
      />

      {dashboard.isPending ? (
        <div className="space-y-6" aria-label="Loading overview">
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            {[0, 1, 2, 3].map((index) => (
              <Skeleton key={index} className="h-24" />
            ))}
          </div>
          <Skeleton className="h-64" />
        </div>
      ) : dashboard.isError ? (
        <Alert variant="destructive">
          <AlertTitle>The overview could not be loaded</AlertTitle>
          <AlertDescription className="space-y-2">
            <p>{dashboard.error.message}</p>
            <Button variant="outline" size="sm" onClick={() => void dashboard.refetch()} disabled={dashboard.isFetching}>
              {dashboard.isFetching ? 'Retrying…' : 'Try again'}
            </Button>
          </AlertDescription>
        </Alert>
      ) : (
        <>
          <section aria-label="Totals" className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <StatTile
              label="Active projects"
              value={dashboard.data.totals.activeProjects}
              detail={`${dashboard.data.totals.archivedProjects} archived`}
              to="/projects"
            />
            <StatTile
              label="Test runs, last 7 days"
              value={dashboard.data.totals.runsLast7Days}
              detail={
                dashboard.data.totals.runsInProgress > 0
                  ? `${dashboard.data.totals.runsInProgress} in progress now`
                  : `${dashboard.data.totals.runsWithFailuresLast7Days} with failures or errors`
              }
            />
            <StatTile
              label="Tests to review"
              value={dashboard.data.totals.testsPendingReview}
              detail="AI suggestions waiting for approval"
              tone="attention"
            />
            <StatTile
              label="Stale tests"
              value={dashboard.data.totals.staleTests}
              detail="Operation changed in a newer specification"
              tone="attention"
            />
          </section>

          <section aria-labelledby="projects-heading" className="space-y-3">
            <div className="flex items-center justify-between gap-3">
              <h2 id="projects-heading" className="text-lg font-semibold tracking-tight">
                Recently active projects
              </h2>
              {dashboard.data.projects.length > 0 ? (
                <Button variant="ghost" size="sm" asChild>
                  <Link to="/projects">
                    All projects
                    <ArrowRight aria-hidden="true" />
                  </Link>
                </Button>
              ) : null}
            </div>
            {dashboard.data.projects.length === 0 ? (
              <Card className="items-center gap-3 px-6 py-12 text-center">
                <FolderPlus className="size-8 text-muted-foreground" aria-hidden="true" />
                <h3 className="font-medium">No projects yet</h3>
                <p className="max-w-md text-sm text-muted-foreground">
                  A project holds one API: its OpenAPI specification, generated tests, environments and run history.
                </p>
                <Button className="mt-2" onClick={() => setCreating(true)}>
                  Create your first project
                </Button>
              </Card>
            ) : (
              <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
                {dashboard.data.projects.map((project) => (
                  <ProjectSummaryCard key={project.id} project={project} />
                ))}
              </div>
            )}
          </section>

        </>
      )}

      <div className="grid gap-6 xl:grid-cols-2">
        {dashboard.data ? (
          <Card className="gap-0 pb-0">
            <CardHeader className="pb-4">
              <CardTitle>Recent test runs</CardTitle>
              <CardDescription>The latest runs across your projects.</CardDescription>
              {dashboard.isFetching ? (
                <CardAction>
                  <span className="text-xs text-muted-foreground" aria-live="polite">
                    Updating…
                  </span>
                </CardAction>
              ) : null}
            </CardHeader>
            <CardContent className="border-t px-0">
              {dashboard.data.recentRuns.length === 0 ? (
                <div className="flex flex-col items-center gap-2 px-6 py-10 text-center text-sm text-muted-foreground">
                  <History className="size-6" aria-hidden="true" />
                  No test runs yet. Start one from a project&apos;s Tests tab.
                </div>
              ) : (
                <ul aria-label="Recent test runs" className="divide-y">
                  {dashboard.data.recentRuns.map((run) => (
                    <RunRefRow key={run.id} run={run} showProject />
                  ))}
                </ul>
              )}
            </CardContent>
          </Card>
        ) : null}
        <SystemStatusCard />
      </div>

      <ProjectFormDialog
        open={creating}
        onOpenChange={setCreating}
        onCreated={(project) => void navigate(`/projects/${project.id}`)}
      />
    </div>
  )
}
