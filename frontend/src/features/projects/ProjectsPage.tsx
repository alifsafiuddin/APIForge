import { Archive, ArchiveRestore, FolderPlus, MoreHorizontal, Pencil, Search, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'

import { ApiError } from '@/api/client'
import { PageHeader } from '@/components/PageHeader'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime, formatRelativeTime } from '@/lib/format'
import { useDebouncedValue } from '@/lib/use-debounced-value'

import { type Project, type ProjectSort, useProjects, useUpdateProject } from './api'
import { DeleteProjectDialog } from './DeleteProjectDialog'
import { ProjectFormDialog } from './ProjectFormDialog'

const PAGE_SIZE = 10

const SORT_OPTIONS: { value: ProjectSort; label: string }[] = [
  { value: 'updatedAt,desc', label: 'Recently updated' },
  { value: 'name,asc', label: 'Name (A–Z)' },
  { value: 'createdAt,desc', label: 'Newest first' },
]

export function ProjectsPage() {
  const [archived, setArchived] = useState(false)
  const [search, setSearch] = useState('')
  const [sort, setSort] = useState<ProjectSort>('updatedAt,desc')
  const [page, setPage] = useState(0)
  const [creating, setCreating] = useState(false)
  const [editing, setEditing] = useState<Project | undefined>(undefined)
  const [deleting, setDeleting] = useState<Project | null>(null)
  const query = useDebouncedValue(search, 300)
  const projects = useProjects({ page, size: PAGE_SIZE, archived, query, sort })
  const update = useUpdateProject()

  const changeFilter = (apply: () => void) => {
    apply()
    setPage(0)
  }

  const toggleArchived = async (project: Project) => {
    try {
      await update.mutateAsync({ id: project.id, version: project.version, archived: !project.archived })
      toast.success(project.archived ? `Restored "${project.name}".` : `Archived "${project.name}".`)
    } catch (error) {
      toast.error(error instanceof ApiError ? error.message : 'The project could not be updated.')
    }
  }

  const data = projects.data
  const totalPages = data?.page.totalPages ?? 0

  return (
    <div className="space-y-6">
      <PageHeader
        title="Projects"
        description="Organize your work by API. Projects are private to your account."
        actions={
          <Button onClick={() => setCreating(true)}>
            <FolderPlus aria-hidden="true" />
            New project
          </Button>
        }
      />

      <div className="flex flex-col gap-3 sm:flex-row sm:items-center">
        <fieldset className="inline-flex rounded-lg border bg-background p-0.5">
          <legend className="sr-only">Project status</legend>
          {[false, true].map((value) => (
            <Button
              key={String(value)}
              variant={archived === value ? 'secondary' : 'ghost'}
              size="sm"
              aria-pressed={archived === value}
              onClick={() => changeFilter(() => setArchived(value))}
            >
              {value ? 'Archived' : 'Active'}
            </Button>
          ))}
        </fieldset>
        <div className="relative flex-1">
          <Search
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            placeholder="Search by name"
            aria-label="Search projects by name"
            className="pl-8"
            value={search}
            maxLength={100}
            onChange={(event) => changeFilter(() => setSearch(event.target.value))}
          />
        </div>
        <select
          aria-label="Sort projects"
          className="h-8 rounded-lg border border-input bg-background px-2.5 text-sm"
          value={sort}
          onChange={(event) => changeFilter(() => setSort(event.target.value as ProjectSort))}
        >
          {SORT_OPTIONS.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
      </div>

      {projects.isError ? (
        <Alert variant="destructive">
          <AlertTitle>Projects could not be loaded</AlertTitle>
          <AlertDescription>
            <p>{projects.error.message}</p>
            <Button variant="outline" size="sm" className="mt-2" onClick={() => void projects.refetch()}>
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      ) : projects.isPending ? (
        <Card className="divide-y p-0" aria-label="Loading projects">
          {Array.from({ length: 3 }, (_, index) => (
            <div key={index} className="space-y-2 p-4">
              <Skeleton className="h-5 w-48" />
              <Skeleton className="h-4 w-72" />
            </div>
          ))}
        </Card>
      ) : data && data.content.length > 0 ? (
        <Card className="gap-0 p-0">
          <ul aria-label="Projects" className="divide-y">
            {data.content.map((project) => (
              <ProjectRow
                key={project.id}
                project={project}
                onEdit={() => setEditing(project)}
                onToggleArchived={() => void toggleArchived(project)}
                onDelete={() => setDeleting(project)}
              />
            ))}
          </ul>
        </Card>
      ) : (
        <EmptyState
          filtered={query.trim().length > 0}
          archived={archived}
          onCreate={() => setCreating(true)}
        />
      )}

      {data && totalPages > 1 ? (
        <nav className="flex items-center justify-between gap-3 text-sm" aria-label="Pagination">
          <span className="text-muted-foreground">
            Page {data.page.number + 1} of {totalPages} · {data.page.totalElements} projects
          </span>
          <div className="flex gap-2">
            <Button variant="outline" size="sm" disabled={page === 0} onClick={() => setPage(page - 1)}>
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={page + 1 >= totalPages}
              onClick={() => setPage(page + 1)}
            >
              Next
            </Button>
          </div>
        </nav>
      ) : null}

      <ProjectFormDialog open={creating} onOpenChange={setCreating} />
      <ProjectFormDialog
        open={editing !== undefined}
        onOpenChange={(open) => !open && setEditing(undefined)}
        project={editing}
      />
      <DeleteProjectDialog project={deleting} onOpenChange={(open) => !open && setDeleting(null)} />
    </div>
  )
}

function ProjectRow({
  project,
  onEdit,
  onToggleArchived,
  onDelete,
}: {
  project: Project
  onEdit: () => void
  onToggleArchived: () => void
  onDelete: () => void
}) {
  return (
    <li className="flex items-start gap-4 p-4">
      <div className="min-w-0 flex-1 space-y-1">
        <div className="flex flex-wrap items-center gap-2">
          <h2 className="truncate font-medium">
            <Link
              to={`/projects/${project.id}`}
              className="rounded-sm hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
            >
              {project.name}
            </Link>
          </h2>
          {project.archived ? <Badge variant="secondary">Archived</Badge> : null}
        </div>
        {project.description ? (
          <p className="line-clamp-2 text-sm text-muted-foreground">{project.description}</p>
        ) : null}
        <p className="text-xs text-muted-foreground">
          Updated <time dateTime={project.updatedAt} title={formatDateTime(project.updatedAt)}>
            {formatRelativeTime(project.updatedAt)}
          </time>
        </p>
      </div>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <Button variant="ghost" size="icon-sm" aria-label={`Actions for ${project.name}`}>
            <MoreHorizontal aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={onEdit}>
            <Pencil aria-hidden="true" />
            Edit
          </DropdownMenuItem>
          <DropdownMenuItem onSelect={onToggleArchived}>
            {project.archived ? <ArchiveRestore aria-hidden="true" /> : <Archive aria-hidden="true" />}
            {project.archived ? 'Restore' : 'Archive'}
          </DropdownMenuItem>
          <DropdownMenuSeparator />
          <DropdownMenuItem variant="destructive" onSelect={onDelete}>
            <Trash2 aria-hidden="true" />
            Delete…
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </li>
  )
}

function EmptyState({ filtered, archived, onCreate }: { filtered: boolean; archived: boolean; onCreate: () => void }) {
  const [title, message] = filtered
    ? ['No matching projects', 'No project name contains your search text.']
    : archived
      ? ['No archived projects', 'Projects you archive appear here and can be restored at any time.']
      : ['No projects yet', 'Create a project for each API you want to test.']
  return (
    <Card className="items-center gap-3 px-6 py-12 text-center">
      <FolderPlus className="size-8 text-muted-foreground" aria-hidden="true" />
      <h2 className="font-medium">{title}</h2>
      <p className="max-w-sm text-sm text-muted-foreground">{message}</p>
      {!filtered && !archived ? (
        <Button onClick={onCreate} className="mt-2">
          New project
        </Button>
      ) : null}
    </Card>
  )
}
