import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { renderApp } from '@/test/render'

/** The shell renders after the session check; wait for its main navigation. */
async function shellReady() {
  return screen.findByRole('navigation', { name: 'Main' })
}

describe('AppShell', () => {
  it('renders the overview inside the shell with the active navigation item marked', async () => {
    renderApp('/')

    expect(await screen.findByRole('heading', { level: 1, name: 'Overview' })).toBeInTheDocument()
    const nav = await shellReady()
    expect(within(nav).getByRole('link', { name: 'Overview' })).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Projects' })).not.toHaveAttribute('aria-current')
  })

  it('shows backend status and the account menu in the header', async () => {
    renderApp('/')

    await shellReady()
    const header = screen.getByRole('banner')
    expect(await within(header).findByText('Operational')).toBeInTheDocument()
    expect(within(header).getByRole('button', { name: 'Account menu for Ada Lovelace' })).toBeInTheDocument()
  })

  it('opens the mobile navigation drawer', async () => {
    const user = userEvent.setup()
    renderApp('/')
    await shellReady()

    await user.click(screen.getByRole('button', { name: 'Open navigation' }))

    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByRole('link', { name: 'Projects' })).toBeInTheDocument()
  })

  it('shows a not-found page for unknown routes without leaving the shell', async () => {
    renderApp('/does-not-exist')

    expect(await screen.findByRole('heading', { name: 'Page not found' })).toBeInTheDocument()
    expect(await shellReady()).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to overview' })).toHaveAttribute('href', '/')
  })
})
