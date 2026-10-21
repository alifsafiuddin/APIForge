import { Badge } from '@/components/ui/badge'
import { StatusBadge } from '@/features/specs/components/MethodBadge'
import { cn } from '@/lib/utils'

import type { Check, Outcome, ResultDetail } from './api'

const OUTCOME_STYLES: Record<Outcome, string> = {
  PASSED: 'bg-emerald-100 text-emerald-800 ring-emerald-200',
  FAILED: 'bg-red-100 text-red-800 ring-red-200',
  ERROR: 'bg-orange-100 text-orange-900 ring-orange-200',
  INCONCLUSIVE: 'bg-amber-100 text-amber-900 ring-amber-200',
  SKIPPED: 'bg-muted text-muted-foreground ring-border',
}

export function OutcomeBadge({ outcome }: { outcome: Outcome }) {
  return (
    <span className={cn('inline-flex rounded px-1.5 py-0.5 text-xs font-semibold ring-1 ring-inset', OUTCOME_STYLES[outcome])}>
      {outcome}
    </span>
  )
}

const CHECK_STYLES: Record<string, string> = {
  PASS: 'text-emerald-700',
  FAIL: 'text-red-700',
  NOT_EVALUATED: 'text-muted-foreground',
}

function pretty(body: string | null, contentType: string | null) {
  if (body === null) return null
  if (contentType?.includes('json')) {
    try {
      return JSON.stringify(JSON.parse(body), null, 2)
    } catch {
      return body
    }
  }
  return body
}

function Pairs({ title, values }: { title: string; values: Record<string, unknown> | null | undefined }) {
  const entries = Object.entries(values ?? {})
  if (entries.length === 0) return null
  return (
    <div>
      <p className="text-xs text-muted-foreground">{title}</p>
      <ul className="font-mono text-xs">
        {entries.map(([name, value]) => (
          <li key={name} className="break-all">
            {name}: {Array.isArray(value) ? value.join(', ') : String(value)}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Everything recorded about one execution. Request and response were redacted before storage. */
export function ResultView({ result }: { result: ResultDetail }) {
  const request = result.request
  const body = request.body as { mode?: string; contentType?: string; value?: unknown; truncated?: boolean } | undefined
  const redirects = Array.isArray(request.redirects) ? (request.redirects as string[]) : []
  return (
    <div className="space-y-4 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <OutcomeBadge outcome={result.outcome} />
        {result.response ? <StatusBadge status={String(result.response.status)} /> : null}
        {result.durationMs !== null ? <span className="text-xs text-muted-foreground">{result.durationMs} ms</span> : null}
        {result.errorKind ? <Badge variant="destructive">{result.errorKind}</Badge> : null}
      </div>
      {result.errorMessage ? <p className={result.outcome === 'SKIPPED' ? 'text-muted-foreground' : 'text-destructive'}>{result.errorMessage}</p> : null}

      {result.checks.length > 0 ? (
        <section aria-label="Checks">
          <h3 className="mb-1 font-medium">Checks</h3>
          <ul className="space-y-1">
            {result.checks.map((check: Check) => (
              <li key={check.name} className="flex gap-2">
                <span className={cn('w-28 shrink-0 font-mono text-xs font-semibold', CHECK_STYLES[check.result])}>
                  {check.result === 'NOT_EVALUATED' ? 'NOT EVALUATED' : check.result} · {check.name}
                </span>
                <span className="text-muted-foreground">{check.message}</span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <section aria-label="Request sent" className="space-y-1.5">
        <h3 className="font-medium">Request</h3>
        <p className="font-mono text-xs break-all">
          {String(request.method ?? '')} {String(request.url ?? request.path ?? '')}
        </p>
        <Pairs title="Headers" values={request.headers as Record<string, unknown> | undefined} />
        {body && body.mode !== 'NONE' && body.value !== undefined ? (
          <div>
            <p className="text-xs text-muted-foreground">
              Body ({body.contentType}){body.truncated ? ', truncated' : ''}
            </p>
            <pre className="max-h-48 overflow-auto rounded-md bg-muted p-2 text-xs">
              {typeof body.value === 'string' ? body.value : JSON.stringify(body.value, null, 2)}
            </pre>
          </div>
        ) : null}
        {redirects.length > 0 ? <Pairs title="Redirects followed" values={Object.fromEntries(redirects.map((url, index) => [String(index + 1), url]))} /> : null}
        {result.correlationId ? (
          <p className="text-xs text-muted-foreground">
            Correlation id <span className="font-mono">{result.correlationId}</span> (sent as X-APIForge-Request-Id)
          </p>
        ) : null}
      </section>

      {result.response ? (
        <section aria-label="Response received" className="space-y-1.5">
          <h3 className="font-medium">Response</h3>
          <Pairs title="Headers" values={result.response.headers} />
          <p className="text-xs text-muted-foreground">
            {result.response.contentType ?? 'no content type'} · {result.response.sizeBytes ?? 0} bytes
            {result.response.truncated ? ' · truncated at the size limit' : ''}
          </p>
          {result.response.body !== null ? (
            <pre className="max-h-72 overflow-auto rounded-md bg-muted p-2 text-xs">{pretty(result.response.body, result.response.contentType)}</pre>
          ) : result.response.sizeBytes ? (
            <p className="text-xs text-muted-foreground">The body is binary and was not stored.</p>
          ) : null}
        </section>
      ) : null}
    </div>
  )
}
