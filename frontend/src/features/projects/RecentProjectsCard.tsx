import { ArrowRight } from 'lucide-react'
import { Link } from 'react-router'

import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatRelativeTime } from '@/lib/format'

import { useProjects } from './api'

/** The five most recently updated active projects, straight from the projects API. */
export function RecentProjectsCard() {
  const projects = useProjects({ page: 0, size: 5, archived: false, query: '', sort: 'updatedAt,desc' })
  const total = projects.data?.page.totalElements

  return (
    <Card>
      <CardHeader>
        <CardTitle>Recent projects</CardTitle>
        <CardDescription>
          {total === undefined ? 'Your active projects.' : `${total} active ${total === 1 ? 'project' : 'projects'}.`}
        </CardDescription>
        <CardAction>
          <Button variant="ghost" size="sm" asChild>
            <Link to="/projects">
              All projects
              <ArrowRight aria-hidden="true" />
            </Link>
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent>
        {projects.isPending ? (
          <div className="space-y-3" aria-label="Loading projects">
            <Skeleton className="h-5 w-full" />
            <Skeleton className="h-5 w-full" />
          </div>
        ) : projects.isError ? (
          <p className="text-sm text-destructive" role="alert">
            {projects.error.message}
          </p>
        ) : projects.data.content.length === 0 ? (
          <p className="text-sm text-muted-foreground">
            No projects yet.{' '}
            <Link to="/projects" className="font-medium text-foreground underline-offset-4 hover:underline">
              Create your first project
            </Link>
            .
          </p>
        ) : (
          <ul className="divide-y" aria-label="Recent projects">
            {projects.data.content.map((project) => (
              <li key={project.id} className="flex items-center justify-between gap-3 py-2.5 text-sm">
                <span className="truncate font-medium">{project.name}</span>
                <time
                  className="shrink-0 text-xs text-muted-foreground"
                  dateTime={project.updatedAt}
                  title={formatDateTime(project.updatedAt)}
                >
                  {formatRelativeTime(project.updatedAt)}
                </time>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
