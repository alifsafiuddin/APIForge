import { mkdirSync, writeFileSync } from 'node:fs'

import { chromium, type FullConfig } from '@playwright/test'

import { USER } from './support'

/**
 * Fails fast with a clear message when the stack is not running, then registers the shared test
 * account once (registration is limited per IP) and stores its signed-in session for the tests.
 */
export default async function globalSetup(config: FullConfig) {
  const { baseURL, channel } = config.projects[0]!.use
  let health: Response
  try {
    health = await fetch(`${baseURL}/actuator/health`)
  } catch {
    throw new Error(`Nothing answers at ${baseURL}. Start PostgreSQL, the backend and the frontend first (README, Quick start).`)
  }
  if (!health.ok) {
    throw new Error(`The backend behind ${baseURL} is not healthy (HTTP ${health.status}).`)
  }

  const browser = await chromium.launch({ channel })
  const page = await browser.newPage({ baseURL })
  await page.goto('/register')
  await page.getByLabel('E-mail').fill(USER.email)
  await page.getByLabel('Display name').fill(USER.name)
  await page.getByLabel('Password', { exact: true }).fill(USER.password)
  await page.getByLabel('Confirm password').fill(USER.password)
  await page.getByRole('button', { name: 'Create account' }).click()
  await page.getByText(`Welcome back, ${USER.name}.`).waitFor()
  mkdirSync('.auth', { recursive: true })
  writeFileSync('.auth/user.json', JSON.stringify(await page.context().storageState()))
  await browser.close()
}
