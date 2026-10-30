import { Search } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { MethodBadge } from '@/features/specs/components/MethodBadge'
import { useDebouncedValue } from '@/lib/use-debounced-value'
import { cn } from '@/lib/utils'

import { CATEGORY_LABELS, useTests } from './api'
import { TEST_VIEWS, type TestView } from './views'

const PAGE_SIZE = 25

/**
 * Every test of the project across endpoints: name search, review/origin/lifecycle views and
 * pagination. The search, view and page live in the URL, so a refresh keeps them.
 */
export function AllTestsPanel({
  projectId,
  view,
  query,
  page,
  onChange,
  onOpen,
}: {
  projectId: string
  view: TestView
  query: string
  page: number
  onChange: (changes: { view?: TestView; query?: string; page?: number }) => void
  onOpen: (testId: string) => void
}) {
  const [draft, setDraft] = useState(query)
  // Back/forward navigation changes the query in the URL: show it in the search box.
  const [shownQuery, setShownQuery] = useState(query)
  if (query !== shownQuery) {
    setShownQuery(query)
    setDraft(query)
  }
  // Only typing (a new debounced value) writes to the URL, never a stale value after navigation.
  const debounced = useDebouncedValue(draft.trim(), 300)
  const lastDebounced = useRef(debounced)
  useEffect(() => {
    if (debounced === lastDebounced.current) return
    lastDebounced.current = debounced
    if (debounced !== query) onChange({ query: debounced, page: 0 })
  }, [debounced, query, onChange])

  const tests = useTests(projectId, { ...TEST_VIEWS[view].filter, q: query || undefined, page, size: PAGE_SIZE }, true, true)
  const totalPages = tests.data?.page.totalPages ?? 0

  return (
    <div className="space-y-3">
      <div className="flex flex-col gap-2 sm:flex-row sm:items-center">
        <div className="relative min-w-0 flex-1">
          <Search
            className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
            aria-hidden="true"
          />
          <Input
            type="search"
            placeholder="Search tests by name"
            aria-label="Search tests by name"
            className="pl-8"
            value={draft}
            maxLength={100}
            onChange={(event) => setDraft(event.target.value)}
          />
        </div>
      </div>
      <fieldset className="m-0 flex min-w-0 flex-wrap gap-1.5 border-0 p-0">
        <legend className="sr-only">Show</legend>
        {(Object.keys(TEST_VIEWS) as TestView[]).map((key) => (
          <Button
            key={key}
            size="sm"
            variant={key === view ? 'secondary' : 'ghost'}
            aria-pressed={key === view}
            onClick={() => onChange({ view: key, page: 0 })}
          >
            {TEST_VIEWS[key].label}
          </Button>
        ))}
      </fieldset>

      <Card className="gap-0 p-0">
        {tests.isPending ? (
          <div className="space-y-2 p-3" aria-label="Loading tests">
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
            <Skeleton className="h-10 w-full" />
          </div>
        ) : tests.isError ? (
          <p className="p-4 text-sm text-destructive" role="alert">
            {tests.error.message}
          </p>
        ) : tests.data.content.length === 0 ? (
          <p className="p-6 text-center text-sm text-muted-foreground">
            {query ? `No tests named like “${query}” in this view.` : 'No tests in this view.'}
          </p>
        ) : (
          <ul aria-label="Matching tests" className={cn('divide-y', tests.isPlaceholderData && 'opacity-60')}>
            <li className="px-3 py-2 text-xs text-muted-foreground" aria-live="polite">
              {tests.data.page.totalElements} {tests.data.page.totalElements === 1 ? 'test' : 'tests'}
            </li>
            {tests.data.content.map((test) => (
              <li key={test.id}>
                <button
                  type="button"
                  onClick={() => onOpen(test.id)}
                  className="w-full min-w-0 px-3 py-2 text-left hover:bg-muted/50 focus-visible:bg-muted/50 focus-visible:outline-none"
                >
                  <span className="flex min-w-0 items-center gap-2">
                    <MethodBadge method={test.method} />
                    <span className="truncate font-mono text-xs text-muted-foreground">{test.path}</span>
                  </span>
                  <span className={cn('mt-1 block truncate text-sm', !test.enabled && 'text-muted-foreground line-through')}>
                    {test.name}
                  </span>
                  <span className="mt-1 flex flex-wrap gap-1.5">
                    <Badge variant="secondary">{CATEGORY_LABELS[test.category]}</Badge>
                    {test.reviewStatus === 'PENDING_REVIEW' ? <Badge variant="destructive">Needs review</Badge> : null}
                    {test.reviewStatus === 'REJECTED' ? <Badge variant="destructive">Rejected</Badge> : null}
                    {test.lifecycleStatus === 'STALE' ? <Badge variant="outline">Stale</Badge> : null}
                    {test.origin === 'AI_SUGGESTED' ? <Badge variant="outline">AI-suggested</Badge> : null}
                    {!test.enabled ? <Badge variant="outline">Disabled</Badge> : null}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>
      {totalPages > 1 ? (
        <nav aria-label="Test pages" className="flex items-center justify-end gap-2 text-sm">
          <Button size="sm" variant="outline" disabled={page === 0} onClick={() => onChange({ page: page - 1 })}>
            Previous
          </Button>
          <span>
            Page {page + 1} of {totalPages}
          </span>
          <Button size="sm" variant="outline" disabled={page + 1 >= totalPages} onClick={() => onChange({ page: page + 1 })}>
            Next
          </Button>
        </nav>
      ) : null}
    </div>
  )
}
