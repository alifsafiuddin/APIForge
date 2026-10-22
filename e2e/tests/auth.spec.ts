import { expect, test } from '@playwright/test'

import { api, createProject, registerSeparateAccount, signIn, unique, USER } from '../support'

test.describe('signed out', () => {
  test.use({ storageState: { cookies: [], origins: [] } })

  test('protected pages send you to sign in and back to where you were', async ({ page }) => {
    await page.goto('/settings')
    await expect(page).toHaveURL(/\/login\?next=%2Fsettings/)

    await page.getByLabel('E-mail').fill(USER.email)
    await page.getByLabel('Password').fill('not-the-password-1')
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toContainText(/e-mail address or password|Invalid e-mail or password/i)

    await page.getByLabel('Password').fill(USER.password)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page).toHaveURL(/\/settings$/)
    await expect(page.getByRole('heading', { level: 1, name: 'Settings' })).toBeVisible()
  })

  test('changing the password signs out every other session', async ({ page, browser }) => {
    const account = await registerSeparateAccount(page, 'pwchange')
    const other = await browser.newContext({ storageState: { cookies: [], origins: [] } })
    const otherPage = await other.newPage()
    await signIn(otherPage, account.email, account.password)
    await expect(otherPage.getByText(`Welcome back, ${account.name}.`)).toBeVisible()

    await page.goto('/settings')
    await page.getByLabel('Current password').fill(account.password)
    await page.getByLabel('New password', { exact: true }).fill('violet-harbour-lantern-42')
    await page.getByLabel('Confirm new password').fill('violet-harbour-lantern-42')
    await page.getByRole('button', { name: 'Change password' }).click()
    await expect(page.getByText(/Password changed/)).toBeVisible()

    expect((await api(page, 'GET', '/api/v1/auth/me')).status).toBe(200)
    await otherPage.getByRole('link', { name: 'Projects' }).first().click()
    await expect(otherPage).toHaveURL(/\/login/)
    await other.close()

    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page).toHaveURL(/\/login/)
    await signIn(page, account.email, 'violet-harbour-lantern-42')
    await expect(page.getByText(`Welcome back, ${account.name}.`)).toBeVisible()
  })

  test('deleting the account removes it and its projects', async ({ page }) => {
    const account = await registerSeparateAccount(page, 'delete')
    await createProject(page, unique('Short-lived'))

    await page.goto('/settings')
    await page.getByRole('button', { name: 'Delete account…' }).click()
    const dialog = page.getByRole('alertdialog', { name: 'Delete your account?' })
    await expect(dialog.getByRole('button', { name: 'Delete account' })).toBeDisabled()
    await dialog.getByLabel(`Type ${account.email} to confirm`).fill(account.email)
    await dialog.getByLabel('Password').fill(account.password)
    await dialog.getByRole('button', { name: 'Delete account' }).click()
    await expect(page).toHaveURL(/\/login/)

    await signIn(page, account.email, account.password)
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })
})

test('signing out ends the session on the server', async ({ browser, baseURL }) => {
  // A separate sign-in so the shared session stays valid for the other journeys.
  const context = await browser.newContext({ storageState: { cookies: [], origins: [] }, baseURL })
  const page = await context.newPage()
  await signIn(page, USER.email, USER.password)
  await expect(page.getByText(`Welcome back, ${USER.name}.`)).toBeVisible()
  const cookies = await context.cookies()

  await page.getByRole('button', { name: `Account menu for ${USER.name}` }).click()
  await page.getByRole('menuitem', { name: 'Sign out' }).click()
  await expect(page).toHaveURL(/\/login/)

  // Replaying the old session cookie no longer works.
  const replay = await browser.newContext({ storageState: { cookies, origins: [] }, baseURL })
  const replayPage = await replay.newPage()
  await replayPage.goto('/projects')
  await expect(replayPage).toHaveURL(/\/login/)
  await context.close()
  await replay.close()
})
