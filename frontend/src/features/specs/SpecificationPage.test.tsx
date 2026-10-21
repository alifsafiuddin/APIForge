import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http } from 'msw'
import { describe, expect, it, vi } from 'vitest'

import { renderApp } from '@/test/render'
import { problem, server } from '@/test/server'

import { MAX_SPEC_BYTES } from './api'
import { matchesQuery } from './operation-filter'
import { mockSpecApi, PROJECT, SAMPLE_VERSION } from './test-fixtures'

const SPEC_PAGE = `/projects/${PROJECT.id}/spec`

async function endpointNav() {
  return screen.findByRole('navigation', { name: 'Endpoints' })
}

describe('Specification import', () => {
  it('starts with an empty state and imports pasted text into a browsable explorer', async () => {
    const state = mockSpecApi({ versions: 0 })
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}`)

    expect(await screen.findByRole('heading', { name: 'No specification yet' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Import specification' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('tab', { name: 'Paste text' }))
    fireEvent.change(within(dialog).getByLabelText('Specification text'), {
      target: { value: 'openapi: 3.0.3\ninfo: {title: Inventory API, version: 1.0.0}\npaths: {}' },
    })
    await user.click(within(dialog).getByRole('button', { name: 'Import' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText('Imported version 1: 3 endpoints.')).toBeInTheDocument()
    expect(await endpointNav()).toBeInTheDocument()
    expect(await screen.findByRole('heading', { name: 'Inventory API' })).toBeInTheDocument()
    expect(state.imports).toHaveLength(1)
  })

  it('uploads a file as multipart form data and shows the import findings', async () => {
    const state = mockSpecApi({ versions: 0 })
    // Vitest's jsdom bridge cannot stream FormData through Node's fetch (content-length mismatch),
    // so the multipart request is checked at the fetch boundary; the real upload is covered end
    // to end against the backend. Every other request still goes through MSW.
    const realFetch = globalThis.fetch
    const uploads: { name: string; csrf: string | null }[] = []
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
      if (init?.body instanceof FormData) {
        const file = init.body.get('file') as File
        uploads.push({ name: file.name, csrf: new Headers(init.headers).get('X-XSRF-TOKEN') })
        state.versions = [SAMPLE_VERSION]
        return new Response(JSON.stringify({ version: SAMPLE_VERSION, messages: [] }), {
          status: 201,
          headers: { 'Content-Type': 'application/json' },
        })
      }
      return realFetch(input, init)
    })
    try {
      const user = userEvent.setup()
      renderApp(SPEC_PAGE)

      await user.click(await screen.findByRole('button', { name: 'Import specification' }))
      const dialog = await screen.findByRole('dialog')
      const file = new File(['openapi: 3.0.3'], 'inventory.yaml', { type: 'application/yaml' })
      await user.upload(within(dialog).getByLabelText('Specification file'), file)
      expect(within(dialog).getByText('inventory.yaml')).toBeInTheDocument()
      await user.click(within(dialog).getByRole('button', { name: 'Import' }))

      const findings = await screen.findByRole('list', { name: 'Import findings' })
      expect(within(findings).getByText(/'oauth'/)).toBeInTheDocument()
      expect(uploads).toEqual([{ name: 'inventory.yaml', csrf: 'test-csrf-token' }])
    } finally {
      spy.mockRestore()
    }
  })

  it('shows unexpected failures instead of failing silently', async () => {
    mockSpecApi({ versions: 0 })
    const realFetch = globalThis.fetch
    const spy = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) =>
      init?.method === 'POST' && String(input).includes('/specs?')
        ? Promise.reject(new TypeError('Failed to fetch'))
        : realFetch(input, init),
    )
    try {
      const user = userEvent.setup()
      renderApp(SPEC_PAGE)

      await user.click(await screen.findByRole('button', { name: 'Import specification' }))
      const dialog = await screen.findByRole('dialog')
      await user.click(within(dialog).getByRole('tab', { name: 'Paste text' }))
      fireEvent.change(within(dialog).getByLabelText('Specification text'), { target: { value: 'openapi: 3.0.3' } })
      await user.click(within(dialog).getByRole('button', { name: 'Import' }))

      expect(await within(dialog).findByText('Could not reach the APIForge backend.')).toBeInTheDocument()
    } finally {
      spy.mockRestore()
    }
  })

  it('lists the problems of a rejected specification without closing the dialog', async () => {
    mockSpecApi({ versions: 0 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    await user.click(await screen.findByRole('button', { name: 'Import specification' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('tab', { name: 'Paste text' }))
    fireEvent.change(within(dialog).getByLabelText('Specification text'), { target: { value: 'openapi: 3.0.3\npaths: {}' } })
    await user.click(within(dialog).getByRole('button', { name: 'Import' }))

    const problems = await within(dialog).findByRole('list', { name: 'Problems found' })
    expect(within(problems).getByText("Required field 'info' is missing.")).toBeInTheDocument()
    expect(within(problems).getByText('(document root)')).toBeInTheDocument()
    expect(within(dialog).getByText('The specification cannot be imported: 1 problem found.')).toBeInTheDocument()
  })

  it('checks a document without importing it', async () => {
    const state = mockSpecApi({ versions: 0 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    await user.click(await screen.findByRole('button', { name: 'Import specification' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('tab', { name: 'Paste text' }))
    fireEvent.change(within(dialog).getByLabelText('Specification text'), { target: { value: 'openapi: 3.0.3' } })
    await user.click(within(dialog).getByRole('button', { name: 'Check' }))

    expect(await within(dialog).findByText('Ready to import')).toBeInTheDocument()
    expect(state.imports).toHaveLength(0)
  })

  it('refuses files above the size limit before uploading', async () => {
    const state = mockSpecApi({ versions: 0 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    await user.click(await screen.findByRole('button', { name: 'Import specification' }))
    const dialog = await screen.findByRole('dialog')
    const big = new File(['x'], 'huge.yaml')
    Object.defineProperty(big, 'size', { value: MAX_SPEC_BYTES + 1 })
    await user.upload(within(dialog).getByLabelText('Specification file'), big)

    expect(within(dialog).getByText(/“huge.yaml” is 5.0 MB; the maximum is 5.0 MB./)).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: 'Import' })).toBeDisabled()
    expect(state.imports).toHaveLength(0)
  })
})

describe('Specification explorer', () => {
  it('shows the version overview with servers, security schemes and findings', async () => {
    mockSpecApi({ versions: 1 })
    renderApp(SPEC_PAGE)

    expect(await screen.findByRole('heading', { name: 'Inventory API' })).toBeInTheDocument()
    expect(screen.getByText('https://inventory.example.test/api')).toBeInTheDocument()
    expect(screen.getByText('bearerAuth')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Download original' })).toHaveAttribute(
      'href',
      `/api/v1/projects/${PROJECT.id}/specs/v-1/raw`,
    )
  })

  it('lists endpoints grouped by tag with method badges, and searches them', async () => {
    mockSpecApi({ versions: 1 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    const nav = await endpointNav()
    expect(within(nav).getByRole('region', { name: 'Tag orders' })).toBeInTheDocument()
    expect(within(nav).getByRole('region', { name: 'Tag products' })).toBeInTheDocument()
    expect(within(nav).getAllByText('GET')).toHaveLength(1)

    await user.type(screen.getByRole('searchbox'), 'product')
    expect(screen.getByText('Showing 1 of 3 endpoints')).toBeInTheDocument()
    expect(within(nav).getByText('/products/{sku}')).toBeInTheDocument()
    expect(within(nav).queryByText('List orders')).not.toBeInTheDocument()
  })

  it('filters endpoints by method', async () => {
    mockSpecApi({ versions: 1 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    await endpointNav()
    await user.click(screen.getByRole('button', { name: 'POST', pressed: false }))

    expect(screen.getByText('Showing 1 of 3 endpoints')).toBeInTheDocument()
    expect(screen.getByText('Create order')).toBeInTheDocument()
  })

  it('shows endpoint detail: parameters, schemas and documented responses', async () => {
    mockSpecApi({ versions: 1 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    await user.click(within(await endpointNav()).getByText('List orders'))

    expect(await screen.findByRole('heading', { name: '/orders', level: 2 })).toBeInTheDocument()
    expect(screen.getByText('Returns orders, newest first.')).toBeInTheDocument()
    const query = screen.getByRole('region', { name: 'query parameters' })
    expect(within(query).getByText('pageSize')).toBeInTheDocument()
    expect(within(query).getByText('max: 100')).toBeInTheDocument()
    const header = screen.getByRole('region', { name: 'header parameters' })
    expect(within(header).getByText('required')).toBeInTheDocument()
    expect(screen.getByText('This endpoint takes no request body.')).toBeInTheDocument()
    const responses = screen.getByRole('list', { name: 'Documented responses' })
    expect(within(responses).getByText('200')).toBeInTheDocument()
    expect(within(responses).getByText('Authentication required')).toBeInTheDocument()
    expect(within(responses).getByText('X-Total-Count')).toBeInTheDocument()
    // The referenced item schema is expanded from the component schemas.
    expect(within(responses).getByText('"SHIPPED"')).toBeInTheDocument()
    expect(screen.getByText(/Authentication \(any one of\)/).parentElement).toHaveTextContent('bearerAuth')
  })

  it('shows per-endpoint import warnings and request bodies', async () => {
    mockSpecApi({ versions: 1 })
    const user = userEvent.setup()
    renderApp(SPEC_PAGE)

    await user.click(within(await endpointNav()).getByText('Create order'))

    expect(await screen.findByText('Callbacks are shown for reference only and are not tested.')).toBeInTheDocument()
    expect(screen.getByText('(required)')).toBeInTheDocument()
    expect(screen.getByText('Authentication: none required.')).toBeInTheDocument()
  })

  it('switches to an older version and makes it active', async () => {
    const state = mockSpecApi({ versions: 2 })
    const user = userEvent.setup()
    const { router } = renderApp(SPEC_PAGE)

    await endpointNav()
    await user.selectOptions(screen.getByLabelText('Version'), 'v-1')
    await waitFor(() => expect(router.state.location.search).toBe('?version=v-1'))
    await user.click(await screen.findByRole('button', { name: 'Make active' }))

    await waitFor(() => expect(state.activations).toEqual(['v-1']))
    expect(await screen.findByText('Version 1 is now active.')).toBeInTheDocument()
  })

  it('treats another user’s project as not found', async () => {
    mockSpecApi({ versions: 0 })
    // Registered last, so it takes precedence over the mock's project handler.
    server.use(http.get(`/api/v1/projects/${PROJECT.id}`, () => problem(404, 'NOT_FOUND', 'Project not found.')))
    renderApp(SPEC_PAGE)

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
  })
})

describe('matchesQuery', () => {
  const operation = {
    id: 'x',
    operationKey: 'GET /pets/{id}',
    method: 'GET',
    path: '/pets/{id}',
    operationId: 'getPet',
    summary: 'Find a pet',
    tags: ['animals'],
    deprecated: false,
    warningCount: 0,
  }

  it('matches path, summary, operationId and tags case-insensitively', () => {
    expect(matchesQuery(operation, 'PETS')).toBe(true)
    expect(matchesQuery(operation, 'find')).toBe(true)
    expect(matchesQuery(operation, 'getpet')).toBe(true)
    expect(matchesQuery(operation, 'animal')).toBe(true)
    expect(matchesQuery(operation, 'orders')).toBe(false)
    expect(matchesQuery(operation, '  ')).toBe(true)
  })
})
