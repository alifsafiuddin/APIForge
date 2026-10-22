import { expect, test } from '@playwright/test'

import { api, createLabEnvironment, createProject, LAB_KEY, unique } from '../support'

test('environments refuse internal destinations and never show secrets again', async ({ page }) => {
  const projectId = await createProject(page, unique('Safety'))
  await page.goto(`/projects/${projectId}/environments`)
  await page.getByRole('button', { name: 'New environment' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('Only send requests to APIs you own or are explicitly authorized to test')
  await dialog.getByLabel('Name').fill('Metadata')
  for (const blocked of ['http://169.254.169.254', 'http://0x7f.0.0.1:8090', 'file:///etc/passwd', 'http://[::ffff:169.254.169.254]/']) {
    await dialog.getByLabel('Base URL').fill(blocked)
    await dialog.getByRole('button', { name: 'Save' }).click()
    await expect(dialog.getByRole('alert').first()).toBeVisible()
  }
  await page.keyboard.press('Escape')
  expect((await api<unknown[]>(page, 'GET', `/api/v1/projects/${projectId}/environments`)).json).toHaveLength(0)

  await createLabEnvironment(page, projectId)
  expect(await page.content()).not.toContain(LAB_KEY)
  const detail = await api(page, 'GET', `/api/v1/projects/${projectId}/environments`)
  expect(JSON.stringify(detail.json)).not.toContain(LAB_KEY)
})

test('irreversible actions ask first and cancelling changes nothing', async ({ page }) => {
  const name = unique('Confirm')
  const projectId = await createProject(page, name)
  await createLabEnvironment(page, projectId)

  const variables = page.getByRole('region', { name: 'Variables of Warehouse Lab (local)' })
  await variables.getByRole('button', { name: 'Delete variable API_KEY' }).click()
  const confirm = page.getByRole('alertdialog', { name: 'Delete variable API_KEY?' })
  await expect(confirm).toContainText('cannot be shown or restored')
  await confirm.getByRole('button', { name: 'Cancel' }).click()
  await expect(variables.getByText('•••••• (write-only)')).toBeVisible()

  await page.goto('/projects')
  await page.getByRole('button', { name: `Actions for ${name}` }).click()
  await page.getByRole('menuitem', { name: 'Delete…' }).click()
  const deletion = page.getByRole('alertdialog')
  await expect(deletion.getByRole('button', { name: 'Delete project' })).toBeDisabled()
  await deletion.getByLabel('Type the project name to confirm').fill(name)
  await deletion.getByRole('button', { name: 'Delete project' }).click()
  await expect(page.getByText(`Project "${name}" deleted.`)).toBeVisible()
  expect((await api(page, 'GET', `/api/v1/projects/${projectId}`)).status).toBe(404)
})
