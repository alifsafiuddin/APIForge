import { expect, test } from '@playwright/test'

import { api, createLabEnvironment, createProject, generateTests, importSpecification, unique } from '../support'

type AiStatus = { state: 'READY' | 'MODEL_MISSING' | 'UNAVAILABLE' | 'DISABLED'; model: string }

/**
 * AI is optional. Run the suite once with Ollama running and once without: each run covers the
 * matching case and skips the other, and the deterministic journey must pass in both.
 */
test('with local AI available, suggestions wait for review and cannot run until accepted', async ({ page }) => {
  await page.goto('/')
  const status = (await api<AiStatus>(page, 'GET', '/api/v1/ai/status?refresh=true')).json
  test.skip(status.state !== 'READY', `local AI is ${status.state}`)
  test.setTimeout(420_000)

  const projectId = await createProject(page, unique('AI'))
  await importSpecification(page, projectId)
  await generateTests(page, projectId)
  await page.getByRole('navigation', { name: 'Endpoints with tests' }).getByRole('button', { name: /^POST\s*\/orders/ }).click()
  const panel = page.getByRole('region', { name: 'AI suggestions' })
  await panel.getByRole('button', { name: 'Suggest tests' }).click()
  await expect(panel.getByText(/added for review|No new suggestion was added/)).toBeVisible({ timeout: 300_000 })

  const pending = await api<{ content: { id: string; enabled: boolean; reviewStatus: string }[] }>(
    page, 'GET', `/api/v1/projects/${projectId}/tests?reviewStatus=PENDING_REVIEW&size=100`)
  for (const suggestion of pending.json.content) {
    const run = await api(page, 'POST', `/api/v1/projects/${projectId}/runs`, { environmentId: '00000000-0000-0000-0000-000000000000', testIds: [suggestion.id] })
    expect(run.status).toBeGreaterThanOrEqual(400)
  }
  await page.goto(`/projects/${projectId}/tests?view=review`)
  if (pending.json.content.length === 0) {
    await expect(page.getByText('No tests in this view.')).toBeVisible()
  } else {
    await expect(page.getByRole('list', { name: 'Matching tests' })).toContainText('Needs review')
  }
})

test('without local AI, AI actions explain why and everything else still works', async ({ page }) => {
  await page.goto('/')
  const status = (await api<AiStatus>(page, 'GET', '/api/v1/ai/status?refresh=true')).json
  test.skip(status.state === 'READY', 'local AI is available')

  // "Off" is how the header labels AI switched off by configuration (APIFORGE_AI_ENABLED=false, as in CI).
  await expect(page.getByRole('button', { name: /^AI: (Unavailable|Model missing|Off)/ })).toBeVisible()
  const projectId = await createProject(page, unique('No AI'))
  await importSpecification(page, projectId)
  const generated = await generateTests(page, projectId)
  await page.getByRole('navigation', { name: 'Endpoints with tests' }).getByRole('button', { name: /^POST\s*\/orders/ }).click()
  const panel = page.getByRole('region', { name: 'AI suggestions' })
  await expect(panel.getByRole('button', { name: 'Suggest tests' })).toBeDisabled()
  const refused = await api<{ code: string }>(page, 'POST', `/api/v1/projects/${projectId}/ai/suggestions`, { operationKey: 'POST /orders' })
  expect(refused.status).toBeGreaterThanOrEqual(500)
  expect(refused.json.code).toMatch(/^AI_/)

  await createLabEnvironment(page, projectId)
  await page.goto(`/projects/${projectId}`)
  await page.getByRole('button', { name: 'Run tests' }).click()
  await page.getByRole('dialog', { name: 'Run tests' }).getByRole('button', { name: 'Start run' }).click()
  await expect(page.getByText('COMPLETED', { exact: true })).toBeVisible({ timeout: 90_000 })
  const runId = page.url().split('/runs/')[1]!.split(/[/?#]/)[0]!
  const run = await api<{ counts: { total: number } }>(page, 'GET', `/api/v1/projects/${projectId}/runs/${runId}`)
  expect(run.json.counts.total).toBe(generated)

  await page.goto(`/projects/${projectId}/coverage`)
  await expect(page.getByRole('region', { name: 'Test design' })).toBeVisible()
})
