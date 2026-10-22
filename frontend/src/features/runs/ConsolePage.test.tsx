import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'

import { mockEnvironmentsApi, STAGING } from '@/features/environments/test-fixtures'
import { mockSpecApi, PROJECT } from '@/features/specs/test-fixtures'
import { renderApp } from '@/test/render'
import { problem, server } from '@/test/server'

const CONSOLE = `/projects/${PROJECT.id}/console`

/** Records console requests; nothing may reach the backend while the form is invalid. */
function mockExecutions(answer: () => Response) {
  const bodies: unknown[] = []
  server.use(
    http.post(`/api/v1/projects/${PROJECT.id}/executions`, async ({ request }) => {
      bodies.push(await request.json())
      return answer()
    }),
  )
  return bodies
}

describe('Console form validation', () => {
  it('names the field whose JSON is invalid and sends nothing', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const bodies = mockExecutions(() => HttpResponse.json({}))
    renderApp(CONSOLE)

    const query = await screen.findByLabelText('Query parameters (JSON object)')
    await user.type(query, '{{"limit": 1')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText(/^Query is not valid JSON/)).toBeInTheDocument()

    await user.clear(query)
    await user.type(screen.getByLabelText('Headers (JSON object)'), '[[1, 2]')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText('Headers must be a JSON object.')).toBeInTheDocument()

    await user.clear(screen.getByLabelText('Headers (JSON object)'))
    await user.selectOptions(screen.getByLabelText('Body mode'), 'JSON')
    await user.type(screen.getByLabelText('Body'), 'not json')
    await user.click(screen.getByRole('button', { name: 'Send' }))
    expect(await screen.findByText(/^Body is not valid JSON/)).toBeInTheDocument()

    expect(bodies).toHaveLength(0)
  })

  it('lists server field errors and keeps the form for correction', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const bodies = mockExecutions(() =>
      problem(400, 'VALIDATION_ERROR', 'One or more fields are invalid.', [
        { field: 'path', message: 'must start with /' },
        { field: 'expectedStatuses[0]', message: 'must be NNN or NXX' },
      ]),
    )
    renderApp(CONSOLE)

    const path = await screen.findByLabelText('Path')
    await user.clear(path)
    await user.type(path, 'orders')
    await user.clear(screen.getByLabelText('Expected statuses'))
    await user.type(screen.getByLabelText('Expected statuses'), '2xx, 9')
    await user.click(screen.getByRole('button', { name: 'Send' }))

    expect(await screen.findByText('path: must start with /')).toBeInTheDocument()
    expect(screen.getByText('expectedStatuses[0]: must be NNN or NXX')).toBeInTheDocument()
    expect(screen.getByLabelText('Path')).toHaveValue('orders')
    expect(bodies).toEqual([expect.objectContaining({ path: 'orders', expectedStatuses: ['2xx', '9'], environmentId: STAGING.id })])
  })

  it('explains blocked destinations, rate limits and an unreachable backend', async () => {
    const user = userEvent.setup()
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([STAGING])
    const answers = [
      problem(400, 'OUTBOUND_BLOCKED', 'The destination 169.254.169.254 is a link-local or cloud metadata address and is not allowed.'),
      problem(429, 'RATE_LIMITED', 'Request limit reached; try again in 12 s.'),
      HttpResponse.error(),
    ]
    mockExecutions(() => answers.shift()!)
    renderApp(CONSOLE)

    const send = await screen.findByRole('button', { name: 'Send' })
    await user.click(send)
    expect(await screen.findByText(/link-local or cloud metadata address/)).toBeInTheDocument()
    await user.click(send)
    expect(await screen.findByText('Request limit reached; try again in 12 s.')).toBeInTheDocument()
    await user.click(send)
    expect(await screen.findByText('Could not reach the APIForge backend.')).toBeInTheDocument()
  })

  it('points to the Environments tab when there is nowhere to send requests', async () => {
    mockSpecApi({ versions: 1 })
    mockEnvironmentsApi([])
    renderApp(CONSOLE)

    const notice = await screen.findByText(/tab to send requests/)
    expect(within(notice).getByRole('link', { name: 'Environments' })).toHaveAttribute('href', `/projects/${PROJECT.id}/environments`)
    expect(screen.queryByRole('button', { name: 'Send' })).not.toBeInTheDocument()
  })
})
