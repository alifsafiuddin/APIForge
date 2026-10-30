import { Search, TriangleAlert } from 'lucide-react'
import { useMemo, useState } from 'react'
import { NavLink } from 'react-router'

import { Input } from '@/components/ui/input'
import { cn } from '@/lib/utils'

import type { OperationSummary } from './api'
import { MethodBadge } from './components/MethodBadge'
import { matchesQuery } from './operation-filter'

const METHOD_ORDER = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS', 'TRACE']

/** Searchable endpoint index, grouped by first tag in document order. */
export function OperationList({ operations, versionQuery }: { operations: OperationSummary[]; versionQuery: string }) {
  const [query, setQuery] = useState('')
  const [methods, setMethods] = useState<string[]>([])

  const presentMethods = useMemo(
    () => METHOD_ORDER.filter((method) => operations.some((operation) => operation.method === method)),
    [operations],
  )
  const filtered = operations.filter(
    (operation) => matchesQuery(operation, query) && (methods.length === 0 || methods.includes(operation.method)),
  )
  const groups = new Map<string, OperationSummary[]>()
  for (const operation of filtered) {
    const tag = operation.tags[0] ?? 'Untagged'
    groups.set(tag, [...(groups.get(tag) ?? []), operation])
  }

  const toggle = (method: string) =>
    setMethods((current) => (current.includes(method) ? current.filter((m) => m !== method) : [...current, method]))

  return (
    <div className="space-y-3">
      <div className="relative">
        <Search
          className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground"
          aria-hidden="true"
        />
        <Input
          type="search"
          placeholder="Search endpoints"
          aria-label="Search endpoints by path, summary, operationId or tag"
          className="pl-8"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>
      <fieldset className="flex flex-wrap gap-1.5">
        <legend className="sr-only">Filter by HTTP method</legend>
        {presentMethods.map((method) => (
          <button
            key={method}
            type="button"
            aria-pressed={methods.includes(method)}
            onClick={() => toggle(method)}
            className={cn(
              'rounded-md border px-2 py-0.5 font-mono text-xs transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
              methods.includes(method) ? 'border-primary bg-primary text-primary-foreground' : 'bg-background hover:bg-muted',
            )}
          >
            {method}
          </button>
        ))}
      </fieldset>
      <p className="text-xs text-muted-foreground" aria-live="polite">
        {filtered.length === operations.length
          ? `${operations.length} endpoints`
          : `Showing ${filtered.length} of ${operations.length} endpoints`}
      </p>
      {filtered.length === 0 ? (
        <p className="py-6 text-center text-sm text-muted-foreground">No endpoints match your filters.</p>
      ) : (
        <nav aria-label="Endpoints" className="space-y-4">
          {/* Keeps the heading outline intact: tag headings (h3) belong to this section. */}
          <h2 className="sr-only">Endpoints</h2>
          {[...groups.entries()].map(([tag, items]) => (
            <section key={tag} aria-label={`Tag ${tag}`}>
              <h3 className="px-2 pb-1 text-xs font-medium tracking-wide text-muted-foreground uppercase">{tag}</h3>
              <ul className="space-y-0.5">
                {items.map((operation) => (
                  <li key={operation.id}>
                    <NavLink
                      to={{ pathname: `operations/${operation.id}`, search: versionQuery }}
                      className={({ isActive }) =>
                        cn(
                          'flex items-start gap-2 rounded-md px-2 py-1.5 text-sm transition-colors focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none',
                          isActive ? 'bg-accent' : 'hover:bg-muted',
                        )
                      }
                    >
                      <MethodBadge method={operation.method} />
                      <span className="min-w-0 flex-1">
                        <span className={cn('block font-mono text-xs break-all', operation.deprecated && 'line-through')}>
                          {operation.path}
                        </span>
                        {operation.summary ? (
                          <span className="block truncate text-xs text-muted-foreground">{operation.summary}</span>
                        ) : null}
                      </span>
                      {operation.warningCount > 0 ? (
                        <TriangleAlert
                          className="mt-0.5 size-3.5 shrink-0 text-amber-600"
                          aria-label={`${operation.warningCount} import warning(s)`}
                        />
                      ) : null}
                    </NavLink>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </nav>
      )}
    </div>
  )
}
