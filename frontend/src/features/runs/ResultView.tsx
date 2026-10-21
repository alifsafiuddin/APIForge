import { Badge } from '@/components/ui/badge'
import { StatusBadge } from '@/features/specs/components/MethodBadge'
import { cn } from '@/lib/utils'

import type { Check, CheckResult, Outcome, ResultDetail } from './api'
import { BodyInspector } from './BodyInspector'
import { checkSummary } from './checks'

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

const CHECK_STYLES: Record<CheckResult, string> = {
  PASS: 'text-emerald-700 dark:text-emerald-400',
  FAIL: 'text-red-700 dark:text-red-400',
  INCONCLUSIVE: 'text-amber-700 dark:text-amber-400',
  SKIPPED: 'text-muted-foreground',
  NOT_EVALUATED: 'text-muted-foreground',
}

const CHECK_LABELS: Record<string, string> = {
  STATUS: 'Expected status',
  STATUS_DOCUMENTED: 'Documented status',
  CONTENT_TYPE: 'Content type',
  JSON_SYNTAX: 'JSON syntax',
  BODY_SCHEMA: 'Body schema',
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

function reveal(path: string) {
  const node = document.querySelector<HTMLElement>(`[data-json-path="${CSS.escape(path)}"]`)
  if (!node) return
  for (let parent = node.parentElement; parent; parent = parent.parentElement) {
    if (parent instanceof HTMLDetailsElement) parent.open = true
  }
  node.scrollIntoView({ block: 'center' })
  node.focus()
}

function Assertions({ checks }: { checks: Check[] }) {
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full text-left text-xs">
        <thead className="bg-muted/60 text-muted-foreground">
          <tr>
            <th scope="col" className="px-2 py-1.5 font-medium">Result</th>
            <th scope="col" className="px-2 py-1.5 font-medium">Check</th>
            <th scope="col" className="px-2 py-1.5 font-medium">Expected</th>
            <th scope="col" className="px-2 py-1.5 font-medium">Actual</th>
            <th scope="col" className="px-2 py-1.5 font-medium">Message</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {checks.map((check, position) => (
            <tr key={position} className={cn('align-top', check.result === 'FAIL' && 'bg-red-50/60 dark:bg-red-950/20')}>
              <td className={cn('px-2 py-1.5 font-mono font-semibold whitespace-nowrap', CHECK_STYLES[check.result])}>
                {check.result === 'NOT_EVALUATED' ? 'NOT EVALUATED' : check.result}
              </td>
              <td className="px-2 py-1.5">
                <span className="whitespace-nowrap">{CHECK_LABELS[check.name] ?? check.name}</span>
                {check.path ? (
                  <button type="button" className="block font-mono text-[11px] text-primary underline-offset-2 hover:underline" onClick={() => reveal(check.path!)}>
                    {check.path}
                  </button>
                ) : null}
                {check.keyword ? <span className="block text-[11px] text-muted-foreground">{check.keyword}</span> : null}
              </td>
              <td className="max-w-40 px-2 py-1.5 font-mono [overflow-wrap:anywhere]">{check.expected ?? <span className="text-muted-foreground">–</span>}</td>
              <td className="max-w-40 px-2 py-1.5 font-mono [overflow-wrap:anywhere]">{check.actual ?? <span className="text-muted-foreground">–</span>}</td>
              <td className="min-w-48 px-2 py-1.5 text-muted-foreground [overflow-wrap:anywhere]">{check.message}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

function Timing({ result }: { result: ResultDetail }) {
  if (result.durationMs === null) return null
  const total = result.durationMs
  const headers = result.timeToHeadersMs
  const finished = new Date(result.executedAt)
  const started = new Date(finished.getTime() - total)
  const time = (date: Date) => date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit', second: '2-digit', fractionalSecondDigits: 3 })
  const waitShare = headers === null || total === 0 ? 100 : Math.min(100, (headers / total) * 100)
  return (
    <section aria-label="Timing" className="space-y-1.5">
      <h3 className="font-medium">Timing</h3>
      <div className="flex h-2 overflow-hidden rounded bg-muted" aria-hidden="true">
        <div className="bg-sky-500" style={{ width: `${waitShare}%` }} />
        {headers !== null ? <div className="bg-emerald-500" style={{ width: `${100 - waitShare}%` }} /> : null}
      </div>
      <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-0.5 text-xs">
        <dt className="text-muted-foreground">Request sent</dt>
        <dd className="font-mono">{time(started)}</dd>
        {headers !== null ? (
          <>
            <dt className="text-muted-foreground">
              <span className="mr-1 inline-block size-2 rounded-sm bg-sky-500" aria-hidden="true" />
              Until response headers
            </dt>
            <dd className="font-mono">{headers} ms</dd>
            <dt className="text-muted-foreground">
              <span className="mr-1 inline-block size-2 rounded-sm bg-emerald-500" aria-hidden="true" />
              Receiving the body
            </dt>
            <dd className="font-mono">{total - headers} ms</dd>
          </>
        ) : null}
        <dt className="text-muted-foreground">Total</dt>
        <dd className="font-mono">{total} ms</dd>
        <dt className="text-muted-foreground">Finished</dt>
        <dd className="font-mono">{time(finished)}</dd>
      </dl>
      {headers === null ? <p className="text-xs text-muted-foreground">No breakdown was recorded for this result.</p> : null}
    </section>
  )
}

/** Everything recorded about one execution. Request and response were redacted before storage. */
export function ResultView({ result }: { result: ResultDetail }) {
  const request = result.request
  const body = request.body as { mode?: string; contentType?: string; value?: unknown; truncated?: boolean } | undefined
  const redirects = Array.isArray(request.redirects) ? (request.redirects as string[]) : []
  const skipped = result.checks.filter((check) => check.result === 'SKIPPED' || check.result === 'NOT_EVALUATED').length
  return (
    <div className="space-y-5 text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <OutcomeBadge outcome={result.outcome} />
        {result.response ? <StatusBadge status={String(result.response.status)} /> : null}
        {result.durationMs !== null ? <span className="text-xs text-muted-foreground">{result.durationMs} ms</span> : null}
        {result.errorKind ? <Badge variant="destructive">{result.errorKind}</Badge> : null}
      </div>
      {result.errorMessage ? <p className={result.outcome === 'SKIPPED' ? 'text-muted-foreground' : 'text-destructive'}>{result.errorMessage}</p> : null}

      {result.checks.length > 0 ? (
        <section aria-label="Checks" className="space-y-1.5">
          <h3 className="font-medium">Assertions</h3>
          <p className="text-xs text-muted-foreground">{checkSummary(result.checks)}</p>
          {result.outcome === 'PASSED' && skipped > 0 ? (
            <p className="text-xs text-amber-800 dark:text-amber-300">
              Passed on the checks that ran; {skipped} {skipped === 1 ? 'check was' : 'checks were'} skipped and {skipped === 1 ? 'is' : 'are'} not
              counted as passed.
            </p>
          ) : null}
          <Assertions checks={result.checks} />
        </section>
      ) : null}

      <Timing result={result} />

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
            <BodyInspector body={result.response.body} contentType={result.response.contentType} truncated={result.response.truncated} checks={result.checks} />
          ) : result.response.sizeBytes ? (
            <p className="text-xs text-muted-foreground">The body is binary and was not stored.</p>
          ) : null}
        </section>
      ) : null}
    </div>
  )
}
