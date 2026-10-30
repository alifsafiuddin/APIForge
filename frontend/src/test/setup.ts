import '@testing-library/jest-dom/vitest'

import { cleanup, configure } from '@testing-library/react'
import { afterAll, afterEach, beforeAll } from 'vitest'

import { clearCookies, server } from './server'

// findBy*/waitFor give up after 1 s by default. Rendering whole routes can take longer on a loaded machine
// (e.g. while the backend's Testcontainers build runs alongside); a real failure still fails, only later.
configure({ asyncUtilTimeout: 4000 })

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
