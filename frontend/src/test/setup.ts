import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { clearCookies, server } from './server'

// Requests without a handler fail the test instead of silently hitting the network.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearCookies()
})
afterAll(() => server.close())
