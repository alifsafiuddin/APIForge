import '@testing-library/jest-dom/vitest'

import { cleanup } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { clearCookies, server } from './server'

// jsdom does not lay out pages, so it has no scrollIntoView; scrolling is irrelevant to the assertions.
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {}
}

// Requests without a handler fail the test instead of silently hitting the network.
beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  clearCookies()
})
afterAll(() => server.close())
