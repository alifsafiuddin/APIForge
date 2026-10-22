import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

import { expect, type Page } from '@playwright/test'

const RUN = process.env.E2E_RUN ?? Date.now().toString(36)

/** The shared account, registered once per run by global-setup.ts. */
export const USER = { email: `e2e-${RUN}@example.test`, name: 'E2E Tester', password: 'quartz-meadow-signal-58' }

export const SPEC_PATH = fileURLToPath(new URL('../samples/warehouse-lab-openapi.yaml', import.meta.url))
export const LAB_URL = 'http://127.0.0.1:8090'
/** The sample server's documented demo key; it protects nothing but the sample. */
export const LAB_KEY = 'lab-demo-key'

let counter = 0
export const unique = (prefix: string) => `${prefix} ${RUN}-${++counter}`

/** A JSON API call from inside the page, with the session cookie and the CSRF header like the app sends. */
export async function api<T = unknown>(page: Page, method: string, path: string, body?: unknown): Promise<{ status: number; json: T }> {
  return page.evaluate(
    async ({ method, path, body }) => {
      const token = decodeURIComponent((document.cookie.match(/XSRF-TOKEN=([^;]+)/) ?? [])[1] ?? '')
      const response = await fetch(path, {
        method,
        headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      const text = await response.text()
      return { status: response.status, json: (text ? JSON.parse(text) : null) as never }
    },
    { method, path, body },
  )
}

/** Creates a project through the UI and returns its id (the page ends on the project overview). */
export async function createProject(page: Page, name: string): Promise<string> {
  await page.goto('/projects')
  await page.getByRole('button', { name: 'New project' }).first().click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByRole('button', { name: 'Create project' }).click()
  await page.getByRole('link', { name }).click()
  await expect(page.getByRole('heading', { level: 1, name })).toBeVisible()
  return page.url().split('/projects/')[1]!.split(/[/?#]/)[0]!
}

/** Imports the Warehouse Lab specification on the project's Specification tab. */
export async function importSpecification(page: Page, projectId: string) {
  await page.goto(`/projects/${projectId}/spec`)
  await page.getByRole('button', { name: 'Import specification' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Specification file').setInputFiles(SPEC_PATH)
  await dialog.getByRole('button', { name: 'Import' }).click()
  await expect(page.getByText('Imported version 1: 7 endpoints.')).toBeVisible()
}

export async function generateTests(page: Page, projectId: string): Promise<number> {
  await page.goto(`/projects/${projectId}/tests`)
  await page.getByRole('button', { name: 'Generate tests' }).first().click()
  await expect(page.getByText(/Generated from version 1/)).toBeVisible()
  const summary = await api<{ total: number }>(page, 'GET', `/api/v1/projects/${projectId}/tests/summary`)
  return summary.json.total
}

/** Creates the Warehouse Lab environment and its write-only API key through the form. */
export async function createLabEnvironment(page: Page, projectId: string, name = 'Warehouse Lab (local)') {
  await page.goto(`/projects/${projectId}/environments`)
  await page.getByRole('button', { name: 'New environment' }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill(name)
  await dialog.getByLabel('Base URL').fill(LAB_URL)
  await dialog.getByLabel('Execution policy').selectOption('ALLOW_DESTRUCTIVE')
  await dialog.getByRole('checkbox', { name: /non-production system/ }).check()
  await dialog.getByLabel('Authentication').selectOption('API_KEY_HEADER')
  await dialog.getByRole('button', { name: 'Save' }).click()
  const variables = page.getByRole('region', { name: `Variables of ${name}` })
  await variables.getByLabel('Variable name').fill('API_KEY')
  await variables.getByLabel('Variable value').fill(LAB_KEY)
  await variables.getByRole('button', { name: 'Save variable' }).click()
  await expect(variables.getByText('•••••• (write-only)')).toBeVisible()
}

/** Registers a separate account in a fresh browser context (for journeys that change or delete it). */
export async function registerSeparateAccount(page: Page, prefix: string) {
  const account = { email: `${prefix}-${RUN}@example.test`, name: `${prefix} user`, password: USER.password }
  await page.goto('/register')
  await page.getByLabel('E-mail').fill(account.email)
  await page.getByLabel('Display name').fill(account.name)
  await page.getByLabel('Password', { exact: true }).fill(account.password)
  await page.getByLabel('Confirm password').fill(account.password)
  await page.getByRole('button', { name: 'Create account' }).click()
  await expect(page.getByText(`Welcome back, ${account.name}.`)).toBeVisible()
  return account
}

export async function signIn(page: Page, email: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

export const specText = () => readFileSync(SPEC_PATH, 'utf8')
