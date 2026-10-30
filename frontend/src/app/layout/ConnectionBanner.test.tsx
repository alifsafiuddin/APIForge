import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'

import { renderApp } from '@/test/render'
import { server } from '@/test/server'

describe('Connection banner', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('explains that the backend is unreachable and clears once it answers again', async () => {
    const user = userEvent.setup()
    let down = true
    server.use(http.get('/actuator/health', () => (down ? HttpResponse.error() : HttpResponse.json({ status: 'UP' }))))
    renderApp('/')

    expect(await screen.findByText(/The APIForge backend is not responding/)).toBeInTheDocument()
    down = false
    await user.click(screen.getByRole('button', { name: 'Check again' }))

    await vi.waitFor(() => expect(screen.queryByText(/The APIForge backend is not responding/)).not.toBeInTheDocument())
  })

  it('says when the browser is offline', async () => {
    renderApp('/')
    await screen.findByRole('navigation', { name: 'Main' })
    const onLine = vi.spyOn(navigator, 'onLine', 'get').mockReturnValue(false)

    act(() => {
      window.dispatchEvent(new Event('offline'))
    })
    expect(await screen.findByText(/You are offline/)).toBeInTheDocument()

    onLine.mockReturnValue(true)
    act(() => {
      window.dispatchEvent(new Event('online'))
    })
    expect(screen.queryByText(/You are offline/)).not.toBeInTheDocument()
  })
})
