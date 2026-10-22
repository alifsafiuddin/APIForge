import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { renderApp } from '@/test/render'
import { problem, server, TEST_USER } from '@/test/server'

describe('Settings', () => {
  it('is reachable from the account menu', async () => {
    const user = userEvent.setup()
    const { router } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Account menu for Ada Lovelace' }))
    await user.click(await screen.findByRole('menuitem', { name: 'Settings' }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/settings')
  })

  it('saves the display name and shows it in the header', async () => {
    const user = userEvent.setup()
    const patches: unknown[] = []
    server.use(
      http.patch('/api/v1/auth/me', async ({ request }) => {
        const body = (await request.json()) as { displayName: string }
        patches.push(body)
        return HttpResponse.json({ ...TEST_USER, displayName: body.displayName })
      }),
    )
    renderApp('/settings')

    const name = await screen.findByLabelText('Display name')
    expect(screen.getByRole('button', { name: 'Save profile' })).toBeDisabled()
    await user.clear(name)
    await user.type(name, 'Grace Hopper')
    await user.click(screen.getByRole('button', { name: 'Save profile' }))

    expect(await screen.findByRole('button', { name: 'Account menu for Grace Hopper' })).toBeInTheDocument()
    expect(patches).toEqual([{ displayName: 'Grace Hopper' }])
    expect(screen.getByLabelText('E-mail')).toHaveValue(TEST_USER.email)
  })

  it('changes the password and maps server field errors onto the form', async () => {
    const user = userEvent.setup()
    const bodies: unknown[] = []
    server.use(
      http.post('/api/v1/auth/password', async ({ request }) => {
        const body = (await request.json()) as { currentPassword: string }
        bodies.push(body)
        return body.currentPassword === 'old-password-123456'
          ? new HttpResponse(null, { status: 204 })
          : problem(400, 'VALIDATION_ERROR', 'One or more fields are invalid.', [
              { field: 'currentPassword', message: 'is not your current password' },
            ])
      }),
    )
    renderApp('/settings')

    await user.type(await screen.findByLabelText('Current password'), 'wrong-password-123')
    await user.type(screen.getByLabelText('New password'), 'violet-harbour-lantern-42')
    await user.type(screen.getByLabelText('Confirm new password'), 'violet-harbour-lantern-4')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('Passwords do not match.')).toBeInTheDocument()
    expect(bodies).toHaveLength(0)

    await user.type(screen.getByLabelText('Confirm new password'), '2')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText('is not your current password')).toBeInTheDocument()
    expect(screen.getByLabelText('Current password')).toHaveAttribute('aria-invalid', 'true')

    await user.clear(screen.getByLabelText('Current password'))
    await user.type(screen.getByLabelText('Current password'), 'old-password-123456')
    await user.click(screen.getByRole('button', { name: 'Change password' }))
    expect(await screen.findByText(/Password changed/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByLabelText('New password')).toHaveValue(''))
    expect(bodies.at(-1)).toEqual({ currentPassword: 'old-password-123456', newPassword: 'violet-harbour-lantern-42' })
  })

  it('deletes the account only after the e-mail is typed and the password is confirmed', async () => {
    const user = userEvent.setup()
    const deletions: unknown[] = []
    server.use(
      http.delete('/api/v1/auth/me', async ({ request }) => {
        const body = (await request.json()) as { password: string }
        deletions.push(body)
        return body.password === 'correct-horse-battery-7'
          ? new HttpResponse(null, { status: 204 })
          : problem(400, 'VALIDATION_ERROR', 'One or more fields are invalid.', [
              { field: 'password', message: 'is not your current password' },
            ])
      }),
    )
    const { router } = renderApp('/settings')

    await user.click(await screen.findByRole('button', { name: 'Delete account…' }))
    const dialog = await screen.findByRole('alertdialog', { name: 'Delete your account?' })
    const confirm = within(dialog).getByRole('button', { name: 'Delete account' })
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText(`Type ${TEST_USER.email} to confirm`), TEST_USER.email.toUpperCase())
    expect(confirm).toBeDisabled()
    await user.type(within(dialog).getByLabelText('Password'), 'not-my-password')
    await user.click(confirm)
    expect(await within(dialog).findByText('is not your current password')).toBeInTheDocument()

    await user.clear(within(dialog).getByLabelText('Password'))
    await user.type(within(dialog).getByLabelText('Password'), 'correct-horse-battery-7')
    server.use(http.get('/api/v1/auth/me', () => problem(401, 'UNAUTHENTICATED', 'Sign in.')))
    await user.click(confirm)

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(deletions.at(-1)).toEqual({ password: 'correct-horse-battery-7', confirmEmail: TEST_USER.email.toUpperCase() })
  })
})
