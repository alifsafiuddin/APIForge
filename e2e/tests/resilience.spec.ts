import { expect, test } from '@playwright/test'

test('offline and unreachable-backend states are explained and clear by themselves', async ({ page, context }) => {
  await page.goto('/projects')
  await expect(page.getByRole('heading', { level: 1, name: 'Projects' })).toBeVisible()

  await context.setOffline(true)
  await expect(page.getByText(/You are offline/)).toBeVisible()
  await context.setOffline(false)
  await expect(page.getByText(/You are offline/)).toBeHidden()

  await page.route(/\/(api|actuator)\//, (route) => route.abort())
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Overview' }).click()
  await expect(page.getByText(/The APIForge backend is not responding/)).toBeVisible()
  await page.unroute(/\/(api|actuator)\//)
  await page.getByRole('button', { name: 'Check again' }).click()
  await expect(page.getByText(/The APIForge backend is not responding/)).toBeHidden()
})

test('pages render without console errors or Content-Security-Policy violations', async ({ page }) => {
  const problems: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error') problems.push(`console: ${message.text()}`)
  })
  page.on('pageerror', (error) => problems.push(`page error: ${error.message}`))
  await page.addInitScript(() => {
    document.addEventListener('securitypolicyviolation', (event) => {
      console.error(`CSP violation: ${event.violatedDirective} ${event.blockedURI}`)
    })
  })
  for (const path of ['/', '/projects', '/settings']) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
  }
  expect(problems).toEqual([])
})

test('the main pages fit a phone screen without horizontal scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  for (const path of ['/', '/projects', '/settings']) {
    await page.goto(path)
    await page.waitForLoadState('networkidle')
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
    expect(overflow, path).toBeLessThanOrEqual(0)
  }
  await page.getByRole('button', { name: 'Open navigation' }).click()
  await expect(page.getByRole('dialog').getByRole('link', { name: 'Projects' })).toBeVisible()
})
