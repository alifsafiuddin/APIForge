import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'

import { mockEnvironmentsApi, STAGING } from './test-fixtures'

describe('Environments', () => {
  it('creates the first environment after the authorization notice and requires confirmation for mutating policies', async () => {
    mockSpecApi({ versions: 1 })
    const state = mockEnvironmentsApi()
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/environments`)

    expect(await screen.findByRole('heading', { name: 'No environments yet' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'New environment' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/Only send requests to APIs you own or are explicitly authorized to test/)).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('Name'), 'Lab')
    await user.type(within(dialog).getByLabelText('Base URL'), 'http://127.0.0.1:8090')
    await user.selectOptions(within(dialog).getByLabelText('Execution policy'), 'ALLOW_MUTATING')
    expect(within(dialog).getByText(/non-production system that I am authorized to test/)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    expect(await within(dialog).findByText('authorizationConfirmed: confirm that the target is authorized')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('checkbox', { name: /non-production system/ }))
    await user.click(within(dialog).getByRole('button', { name: 'Save' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(state.created.at(-1)).toMatchObject({ name: 'Lab', authorizationConfirmed: true, executionPolicy: 'ALLOW_MUTATING' })
    expect(await screen.findByRole('heading', { name: 'Lab' })).toBeInTheDocument()
  })

  it('keeps secret variables write-only', async () => {
    mockSpecApi({ versions: 1 })
    const state = mockEnvironmentsApi([STAGING])
    const user = userEvent.setup()
    renderApp(`/projects/${PROJECT.id}/environments`)

    const card = await screen.findByRole('region', { name: 'Variables of Staging' })
    expect(within(card).getByText('•••••• (write-only)')).toBeInTheDocument()
    await user.type(within(card).getByLabelText('Variable name'), 'TOKEN')
    const value = within(card).getByLabelText('Variable value')
    expect(value).toHaveAttribute('type', 'password')
    await user.type(value, 'top-secret')
    await user.click(within(card).getByRole('button', { name: 'Save variable' }))

    expect(await screen.findByText('Secret TOKEN saved (it cannot be shown again).')).toBeInTheDocument()
    expect(state.variables).toEqual([{ name: 'TOKEN', value: 'top-secret', secret: true }])
    expect(screen.queryByText('top-secret')).not.toBeInTheDocument()
  })
})
