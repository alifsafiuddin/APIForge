import { defineConfig } from '@playwright/test'

/**
 * End-to-end tests against a running stack: PostgreSQL, the backend (dev profile) and the frontend
 * dev server must be up (see README "Quick start"). Playwright starts the Warehouse Lab sample API
 * itself, the only target the tests send requests to.
 *
 * E2E_BASE_URL     frontend URL (default http://localhost:5173)
 * E2E_BROWSER      "msedge" or "chrome" to use an installed browser; default: Playwright's Chromium
 *                  (install it once with `npx playwright install chromium`)
 */
const baseURL = process.env.E2E_BASE_URL ?? 'http://localhost:5173'
// One id per run, shared by global setup and the test workers (they inherit the environment).
process.env.E2E_RUN ??= Date.now().toString(36)

export default defineConfig({
  testDir: './tests',
  // One shared database and per-IP sign-in limits: run journeys one after another.
  workers: 1,
  fullyParallel: false,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 120_000,
  expect: { timeout: 15_000 },
  reporter: [['list'], ['html', { open: 'never' }]],
  globalSetup: './global-setup.ts',
  use: {
    baseURL,
    storageState: '.auth/user.json',
    channel: process.env.E2E_BROWSER || undefined,
    headless: true,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'node ../samples/warehouse-lab-server.mjs',
    url: 'http://127.0.0.1:8090/health',
    reuseExistingServer: true,
    timeout: 30_000,
  },
})
