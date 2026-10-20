import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { renderApp } from '@/test/render'

describe('AppShell', () => {
  it('renders the overview inside the shell with the active navigation item marked', async () => {
    renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: 'Main' })
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
  })

  it('shows backend status in the header', async () => {
    renderApp('/')

    const header = screen.getByRole('banner')
    expect(await within(header).findByText('Operational')).toBeInTheDocument()
  })

  it('opens the mobile navigation drawer', async () => {
    const user = userEvent.setup()
    renderApp('/')

    await user.click(screen.getByRole('button', { name: 'Open navigation' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('link', { name: 'Overview' })).toBeInTheDocument()
  })

  it('shows a not-found page for unknown routes without leaving the shell', async () => {
    renderApp('/does-not-exist')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to overview' })).toHaveAttribute('href', '/')
  })
})
