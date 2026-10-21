import { ChevronRight } from 'lucide-react'
import { Link, NavLink, Outlet, useParams } from 'react-router'

import { ApiError } from '@/api/client'
import { NotFoundPage } from '@/app/RouteErrorPage'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { useProject } from './api'

const SECTIONS = [{ to: 'spec', label: 'Specification' }]

/** Frame for one project: breadcrumb, title and section navigation. */
export function ProjectLayout() {
  const { projectId = '' } = useParams()
  const project = useProject(projectId)

  if (project.isError && project.error instanceof ApiError && project.error.status === 404) {
    return <NotFoundPage />
  }

  return (
    <div className="space-y-6">
      <div className="space-y-2">
        <nav aria-label="Breadcrumb" className="flex items-center gap-1 text-sm text-muted-foreground">
          <Link to="/projects" className="hover:text-foreground">
            Projects
          </Link>
          <ChevronRight className="size-3.5" aria-hidden="true" />
          <span className="truncate text-foreground">{project.data?.name ?? '…'}</span>
        </nav>
        {project.isPending ? (
          <Skeleton className="h-8 w-64" />
        ) : project.isError ? (
          <Alert variant="destructive">
            <AlertDescription>{project.error.message}</AlertDescription>
          </Alert>
        ) : (
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="text-2xl font-semibold tracking-tight">{project.data.name}</h1>
            {project.data.archived ? <Badge variant="secondary">Archived</Badge> : null}
          </div>
        )}
        {project.data?.description ? (
          <p className="max-w-3xl text-sm whitespace-pre-wrap text-muted-foreground">{project.data.description}</p>
        ) : null}
      </div>
      <nav aria-label="Project sections" className="flex gap-1 border-b">
        {SECTIONS.map((section) => (
          <NavLink
            key={section.to}
            to={section.to}
            className={({ isActive }) =>
              cn(
                '-mb-px border-b-2 px-3 py-2 text-sm font-medium transition-colors',
                isActive ? 'border-primary text-foreground' : 'border-transparent text-muted-foreground hover:text-foreground',
              )
            }
          >
            {section.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
