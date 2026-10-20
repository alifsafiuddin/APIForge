import { screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { renderApp } from '@/test/render'
import { server } from '@/test/server'

function statusCard() {
  return screen.getByText('System status').closest('[data-slot="card"]') as HTMLElement
}

describe('SystemStatusCard', () => {
  it('shows live component health and build information', async () => {
    renderApp('/')

    const components = await screen.findByRole('list', { name: 'Health components' })
    expect(within(components).getByText('PostgreSQL')).toBeInTheDocument()
    expect(within(components).getAllByText('Operational')).toHaveLength(3)
    expect(await within(statusCard()).findByText('0.1.0-test')).toBeInTheDocument()
    expect(within(statusCard()).getByText('v1')).toBeInTheDocument()
  })

  it('shows a down database when the backend reports 503', async () => {
    server.use(
      http.get('/actuator/health', () =>
        HttpResponse.json({ status: 'DOWN', components: { db: { status: 'DOWN' } } }, { status: 503 }),
      ),
    )
    renderApp('/')

    const components = await screen.findByRole('list', { name: 'Health components' })
    const dbRow = within(components).getByText('PostgreSQL').closest('li') as HTMLElement
    expect(within(dbRow).getByText('Down')).toBeInTheDocument()
  })

  it('explains how to start the backend when it is unreachable', async () => {
    server.use(http.get('/actuator/health', () => HttpResponse.error()))
    renderApp('/')

    expect(await screen.findByText('Backend unavailable')).toBeInTheDocument()
    expect(within(statusCard()).getByText('Unreachable')).toBeInTheDocument()
  })
})
