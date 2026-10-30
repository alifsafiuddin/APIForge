import type { TestFilter } from './api'

/** Project-wide views of the test finder; each maps to list filters of the tests API. */
export const TEST_VIEWS = {
  all: { label: 'All tests', filter: {} },
  review: { label: 'Needs review', filter: { reviewStatus: 'PENDING_REVIEW' } },
  ai: { label: 'AI-suggested', filter: { origin: 'AI_SUGGESTED' } },
  stale: { label: 'Stale', filter: { lifecycleStatus: 'STALE' } },
  disabled: { label: 'Disabled', filter: { enabled: false } },
} as const satisfies Record<string, { label: string; filter: TestFilter }>
export type TestView = keyof typeof TEST_VIEWS
