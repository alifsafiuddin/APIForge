import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { initials } from '@/lib/initials'
import { renderApp } from '@/test/render'
import { CSRF_TOKEN, emptyPage, problem, server, TEST_USER } from '@/test/server'

function signedOut() {
  server.use(http.get('/api/v1/auth/me', () => problem(401, 'UNAUTHENTICATED', 'Authentication is required.')))
}

describe('route protection', () => {
  it('sends signed-out users to the login page and remembers the target', async () => {
    signedOut()
    const { router } = renderApp('/projects')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
    expect(router.state.location.search).toBe('?next=%2Fprojects')
  })

  it('redirects signed-in users away from the login page', async () => {
    const { router } = renderApp('/login?next=/projects')

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects'))
  })

  it('shows a retryable error when the session cannot be checked', async () => {
    server.use(http.get('/api/v1/auth/me', () => HttpResponse.error()))
    renderApp('/')

    expect(await screen.findByRole('heading', { name: 'Cannot reach APIForge' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('returns to the login page when the session expires mid-use', async () => {
    const { router } = renderApp('/projects')
    await screen.findByRole('heading', { level: 1, name: 'Projects' })

    server.use(
      http.get('/api/v1/auth/me', () => problem(401, 'UNAUTHENTICATED', 'Authentication is required.')),
      http.get('/api/v1/projects', () => problem(401, 'UNAUTHENTICATED', 'Authentication is required.')),
    )
    await userEvent.setup().click(screen.getByRole('button', { name: 'Archived' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
  })
})

describe('LoginPage', () => {
  it('signs in with CSRF protection and continues to the requested page', async () => {
    signedOut()
    let loginRequest: { body: unknown; csrf: string | null } | undefined
    server.use(
      http.post('/api/v1/auth/login', async ({ request }) => {
        loginRequest = { body: await request.json(), csrf: request.headers.get('X-XSRF-TOKEN') }
        return HttpResponse.json(TEST_USER)
      }),
    )
    const user = userEvent.setup()
    const { router } = renderApp('/login?next=%2Fprojects')

    await user.type(await screen.findByLabelText('E-mail'), 'ada@example.test')
    await user.type(screen.getByLabelText('Password'), 'correct-horse-battery-7')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/projects'))
    expect(loginRequest).toEqual({
      body: { email: 'ada@example.test', password: 'correct-horse-battery-7' },
      csrf: CSRF_TOKEN,
    })
  })

  it('shows the server message for invalid credentials and stays on the page', async () => {
    signedOut()
    server.use(
      http.post('/api/v1/auth/login', () =>
        problem(401, 'BAD_CREDENTIALS', 'Invalid e-mail or password. After repeated failures, sign-in is temporarily locked.'),
      ),
    )
    const user = userEvent.setup()
    const { router } = renderApp('/login')

    await user.type(await screen.findByLabelText('E-mail'), 'ada@example.test')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText(/Invalid e-mail or password/)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/login')
  })

  it('validates required fields before calling the server', async () => {
    signedOut()
    const user = userEvent.setup()
    renderApp('/login')

    await user.click(await screen.findByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Enter your e-mail address.')).toBeInTheDocument()
    expect(screen.getByText('Enter your password.')).toBeInTheDocument()
  })
})

describe('RegisterPage', () => {
  async function fillForm(password = 'correct-horse-battery-7', confirm = password) {
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('E-mail'), 'ada@example.test')
    await user.type(screen.getByLabelText('Display name'), 'Ada Lovelace')
    await user.type(screen.getByLabelText('Password'), password)
    await user.type(screen.getByLabelText('Confirm password'), confirm)
    await user.click(screen.getByRole('button', { name: 'Create account' }))
  }

  it('registers and lands on the overview', async () => {
    signedOut()
    server.use(http.post('/api/v1/auth/register', () => HttpResponse.json(TEST_USER, { status: 201 })))
    const { router } = renderApp('/register')

    await fillForm()

    await waitFor(() => expect(router.state.location.pathname).toBe('/'))
    expect(await screen.findByText('Welcome back, Ada Lovelace.')).toBeInTheDocument()
  })

  it('checks password length and confirmation locally', async () => {
    signedOut()
    renderApp('/register')

    await fillForm('short', 'different')

    expect(await screen.findByText('Use at least 12 characters.')).toBeInTheDocument()
    expect(screen.getByText('Passwords do not match.')).toBeInTheDocument()
  })

  it('shows server-side field errors next to the fields', async () => {
    signedOut()
    server.use(
      http.post('/api/v1/auth/register', () =>
        problem(400, 'VALIDATION_ERROR', 'One or more fields are invalid.', [
          { field: 'password', message: 'is too common; choose a less predictable password' },
        ]),
      ),
    )
    renderApp('/register')

    await fillForm('password1234')

    expect(await screen.findByText('is too common; choose a less predictable password')).toBeInTheDocument()
  })

  it('reports an e-mail address that is already registered on the e-mail field', async () => {
    signedOut()
    server.use(
      http.post('/api/v1/auth/register', () =>
        problem(409, 'EMAIL_TAKEN', 'An account with this e-mail address already exists.'),
      ),
    )
    renderApp('/register')

    await fillForm()

    expect(await screen.findByText('An account with this e-mail address already exists.')).toBeInTheDocument()
    expect(screen.getByLabelText('E-mail')).toHaveAttribute('aria-invalid', 'true')
  })
})

describe('UserMenu', () => {
  it('signs out and returns to the login page', async () => {
    let loggedOut = false
    server.use(
      http.post('/api/v1/auth/logout', () => {
        loggedOut = true
        return new HttpResponse(null, { status: 204 })
      }),
      http.get('/api/v1/projects', () => HttpResponse.json(emptyPage(5))),
    )
    const user = userEvent.setup()
    const { router } = renderApp('/')

    await user.click(await screen.findByRole('button', { name: 'Account menu for Ada Lovelace' }))
    const menu = await screen.findByRole('menu')
    expect(within(menu).getByText('ada@example.test')).toBeInTheDocument()
    await user.click(within(menu).getByRole('menuitem', { name: 'Sign out' }))

    await waitFor(() => expect(router.state.location.pathname).toBe('/login'))
    expect(loggedOut).toBe(true)
  })

  it('derives initials from the display name', () => {
    expect(initials('Ada Lovelace')).toBe('AL')
    expect(initials('Grace')).toBe('GR')
    expect(initials('  Alan  Mathison  Turing ')).toBe('AT')
  })
})
