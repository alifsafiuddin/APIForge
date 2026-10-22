import { expect, test } from '@playwright/test'

import { api, createLabEnvironment, createProject, generateTests, importSpecification, unique } from '../support'

type Run = { id: string; status: string; counts: { total: number; passed: number; failed: number; error: number } }

/**
 * The core journey: project → specification → generated tests → environment → run → contract
 * failure details → history and coverage → dashboard. Every number shown is compared with the API.
 */
test('import, generate, run and inspect a contract failure end to end', async ({ page }) => {
  const name = unique('Warehouse')
  const projectId = await createProject(page, name)
  const base = `/api/v1/projects/${projectId}`
  await expect(page.getByText('0 of 4 steps done.')).toBeVisible()

  await importSpecification(page, projectId)
  await expect(page.getByRole('navigation', { name: 'Endpoints' }).getByRole('link')).toHaveCount(7)

  const generated = await generateTests(page, projectId)
  expect(generated).toBeGreaterThan(100)
  await expect(page.getByRole('list', { name: 'Matching tests' })).toContainText(`${generated} tests`)

  await createLabEnvironment(page, projectId)

  await page.goto(`/projects/${projectId}`)
  await expect(page.getByText('3 of 4 steps done.')).toBeVisible()
  await page.getByRole('button', { name: 'Run tests' }).click()
  const dialog = page.getByRole('dialog', { name: 'Run tests' })
  await dialog.getByRole('radio', { name: 'All enabled tests of the project' }).check()
  await dialog.getByRole('button', { name: 'Start run' }).click()
  await expect(page.getByRole('heading', { name: 'Test run' })).toBeVisible()
  await expect(page.getByText('COMPLETED', { exact: true })).toBeVisible({ timeout: 90_000 })
  const runId = page.url().split('/runs/')[1]!.split(/[/?#]/)[0]!
  const run = (await api<Run>(page, 'GET', `${base}/runs/${runId}`)).json
  expect(run.counts.total).toBe(generated)

  // The sample accepts order quantities above the documented maximum: a real contract failure.
  expect(run.counts.failed).toBeGreaterThan(0)
  await page.getByRole('button', { name: /^Failed/ }).first().click()
  await page.getByRole('list', { name: 'Results' }).getByRole('button').first().click()
  const sheet = page.getByRole('dialog')
  await expect(sheet.getByRole('table')).toBeVisible()
  await expect(sheet).toContainText('FAIL')
  await expect(sheet).toContainText('«redacted»')
  await expect(sheet).not.toContainText('lab-demo-key')
  await page.keyboard.press('Escape')

  await page.getByRole('navigation', { name: 'Project sections' }).getByRole('link', { name: 'Runs' }).click()
  await page.getByLabel('Result').selectOption('failing')
  await expect(page.getByText('1 run', { exact: true })).toBeVisible()

  await page.getByRole('navigation', { name: 'Project sections' }).getByRole('link', { name: 'Coverage' }).click()
  await expect(page.getByRole('region', { name: 'Test design' })).toContainText('7')

  await page.goto('/')
  const card = page.getByRole('article', { name })
  await expect(card).toContainText(`v1 · 7 operations`)
  await expect(card).toContainText(`${run.counts.passed} of ${run.counts.total} passed`)
})
