import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { renderApp } from '@/test/render'
import { problem, server } from '@/test/server'

import type { Project } from './api'

/** A small in-memory stand-in for the projects API, enough to exercise the page end to end. */
function mockProjectsApi(initial: Partial<Project>[]) {
  const projects: Project[] = initial.map((partial, index) => ({
    id: `p-${index + 1}`,
    name: `Project ${index + 1}`,
    description: null,
    archived: false,
    archivedAt: null,
    activeSpecificationVersionId: null,
    createdAt: '2026-10-20T10:00:00Z',
    updatedAt: '2026-10-20T10:00:00Z',
    version: 0,
    ...partial,
  }))
  const requests: URLSearchParams[] = []

  server.use(
    http.get('/api/v1/projects', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      const page = Number(params.get('page') ?? 0)
      const size = Number(params.get('size') ?? 20)
      const archived = params.get('archived') === 'true'
      const query = (params.get('q') ?? '').toLowerCase()
      const matching = projects.filter(
        (project) => project.archived === archived && project.name.toLowerCase().includes(query),
      )
      return HttpResponse.json({
        content: matching.slice(page * size, page * size + size),
        page: { number: page, size, totalElements: matching.length, totalPages: Math.ceil(matching.length / size) },
      })
    }),
    http.post('/api/v1/projects', async ({ request }) => {
      const body = (await request.json()) as { name: string; description: string }
      if (projects.some((project) => !project.archived && project.name.toLowerCase() === body.name.trim().toLowerCase())) {
        return problem(409, 'DUPLICATE_NAME', `You already have an active project named '${body.name}'.`)
      }
      const created: Project = {
        id: `p-${projects.length + 1}`,
        name: body.name.trim(),
        description: body.description || null,
        archived: false,
        archivedAt: null,
        activeSpecificationVersionId: null,
        createdAt: '2026-10-21T10:00:00Z',
        updatedAt: '2026-10-21T10:00:00Z',
        version: 0,
      }
      projects.unshift(created)
      return HttpResponse.json(created, { status: 201 })
    }),
    http.patch('/api/v1/projects/:id', async ({ params, request }) => {
      const body = (await request.json()) as { version: number; archived?: boolean; name?: string }
      const project = projects.find((candidate) => candidate.id === params.id)
      if (!project) {
        return problem(404, 'NOT_FOUND', 'Project not found.')
      }
      if (body.version !== project.version) {
        return problem(409, 'STALE_VERSION', 'The project was modified since you loaded it.')
      }
      Object.assign(project, {
        ...(body.name !== undefined ? { name: body.name } : {}),
        ...(body.archived !== undefined ? { archived: body.archived } : {}),
        version: project.version + 1,
      })
      return HttpResponse.json(project)
    }),
    http.delete('/api/v1/projects/:id', ({ params }) => {
      const index = projects.findIndex((candidate) => candidate.id === params.id)
      projects.splice(index, 1)
      return new HttpResponse(null, { status: 204 })
    }),
  )
  return { projects, requests }
}

async function projectNames() {
  const list = await screen.findByRole('list', { name: 'Projects' })
  return within(list)
    .getAllByRole('heading', { level: 2 })
    .map((heading) => heading.textContent)
}

describe('ProjectsPage', () => {
  it('lists the user’s projects', async () => {
    mockProjectsApi([{ name: 'Petstore', description: 'Demo API' }, { name: 'Billing' }])
    renderApp('/projects')

    expect(await projectNames()).toEqual(['Petstore', 'Billing'])
    expect(screen.getByText('Demo API')).toBeInTheDocument()
  })

  it('shows an empty state with a create action', async () => {
    mockProjectsApi([])
    renderApp('/projects')

    expect(await screen.findByRole('heading', { name: 'No projects yet' })).toBeInTheDocument()
  })

  it('creates a project and shows it in the list', async () => {
    mockProjectsApi([{ name: 'Existing' }])
    const user = userEvent.setup()
    renderApp('/projects')

    await user.click(await screen.findByRole('button', { name: 'New project' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Inventory API')
    await user.type(within(dialog).getByLabelText('Description (optional)'), 'Stock levels')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await projectNames()).toContain('Inventory API')
    expect(await screen.findByText('Project "Inventory API" created.')).toBeInTheDocument()
  })

  it('shows a duplicate name error on the name field', async () => {
    mockProjectsApi([{ name: 'Inventory' }])
    const user = userEvent.setup()
    renderApp('/projects')

    await user.click(await screen.findByRole('button', { name: 'New project' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'inventory')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    expect(await within(dialog).findByText("You already have an active project named 'inventory'.")).toBeInTheDocument()
    expect(within(dialog).getByLabelText('Name')).toHaveAttribute('aria-invalid', 'true')
  })

  it('rejects blank names without calling the server', async () => {
    const { projects } = mockProjectsApi([])
    const user = userEvent.setup()
    renderApp('/projects')

    await user.click(await screen.findByRole('button', { name: 'New project' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), '   ')
    await user.click(within(dialog).getByRole('button', { name: 'Create project' }))

    expect(await within(dialog).findByText('Enter a project name.')).toBeInTheDocument()
    expect(projects).toHaveLength(0)
  })

  it('requires typing the project name before deleting', async () => {
    const { projects } = mockProjectsApi([{ name: 'Legacy API' }])
    const user = userEvent.setup()
    renderApp('/projects')

    await user.click(await screen.findByRole('button', { name: 'Actions for Legacy API' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Delete…' }))
    const dialog = await screen.findByRole('alertdialog')
    const deleteButton = within(dialog).getByRole('button', { name: 'Delete project' })
    expect(deleteButton).toBeDisabled()

    await user.type(within(dialog).getByLabelText('Type the project name to confirm'), 'Legacy API')
    expect(deleteButton).toBeEnabled()
    await user.click(deleteButton)

    expect(await screen.findByRole('heading', { name: 'No projects yet' })).toBeInTheDocument()
    expect(projects).toHaveLength(0)
  })

  it('archives a project and lists it under Archived', async () => {
    mockProjectsApi([{ name: 'Old service' }, { name: 'Current' }])
    const user = userEvent.setup()
    renderApp('/projects')

    await user.click(await screen.findByRole('button', { name: 'Actions for Old service' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Archive' }))

    await waitFor(async () => expect(await projectNames()).toEqual(['Current']))
    await user.click(screen.getByRole('button', { name: 'Archived' }))
    await waitFor(async () => expect(await projectNames()).toEqual(['Old service']))
  })

  it('pages through results', async () => {
    const { requests } = mockProjectsApi(Array.from({ length: 12 }, (_, index) => ({ name: `Service ${index + 1}` })))
    const user = userEvent.setup()
    renderApp('/projects')

    expect(await screen.findByText('Page 1 of 2 · 12 projects')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Next' }))

    expect(await screen.findByText('Page 2 of 2 · 12 projects')).toBeInTheDocument()
    expect(await projectNames()).toEqual(['Service 11', 'Service 12'])
    expect(requests.at(-1)?.get('page')).toBe('1')
  })

  it('searches by name', async () => {
    const { requests } = mockProjectsApi([{ name: 'Payments' }, { name: 'Users' }])
    const user = userEvent.setup()
    renderApp('/projects')

    await user.type(await screen.findByRole('searchbox', { name: 'Search projects by name' }), 'pay')

    await waitFor(async () => expect(await projectNames()).toEqual(['Payments']))
    expect(requests.at(-1)?.get('q')).toBe('pay')
  })

  it('shows a load error with retry', async () => {
    server.use(http.get('/api/v1/projects', () => problem(500, 'INTERNAL_ERROR', 'An unexpected error occurred.')))
    renderApp('/projects')

    expect(await screen.findByText('Projects could not be loaded')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })
})
