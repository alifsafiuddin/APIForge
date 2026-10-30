import { useMemo, useState } from 'react'

import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'

import type { Check } from './api'
import { childPath, parsePath, toPath } from './jsonPath'

type Finding = { path: string; message: string }

type Index = {
  /** Findings at a node that exists in the body. */
  at: Map<string, Finding[]>
  /** Properties reported at a path that is absent (e.g. a missing required field), by parent path. */
  missing: Map<string, { key: string; findings: Finding[] }[]>
  /** Paths with a finding somewhere below; kept expanded. */
  ancestors: Set<string>
  /** Findings whose path could not be placed in the body. */
  unplaced: Finding[]
}

function isContainer(value: unknown): value is Record<string, unknown> | unknown[] {
  return typeof value === 'object' && value !== null
}

function lookup(value: unknown, key: string | number): { found: boolean; value?: unknown } {
  if (Array.isArray(value)) {
    const index = Number(key)
    return Number.isInteger(index) && index >= 0 && index < value.length ? { found: true, value: value[index] } : { found: false }
  }
  if (isContainer(value) && Object.hasOwn(value, String(key))) return { found: true, value: (value as Record<string, unknown>)[String(key)] }
  return { found: false }
}

function index(root: unknown, findings: Finding[]): Index {
  const result: Index = { at: new Map(), missing: new Map(), ancestors: new Set(), unplaced: [] }
  for (const finding of findings) {
    const tokens = parsePath(finding.path)
    if (tokens === null) {
      result.unplaced.push(finding)
      continue
    }
    let current: unknown = root
    let depth = 0
    for (const token of tokens) {
      const next = lookup(current, token)
      if (!next.found) break
      current = next.value
      depth += 1
    }
    const path = toPath(tokens)
    if (depth === tokens.length) {
      result.at.set(path, [...(result.at.get(path) ?? []), finding])
    } else if (depth === tokens.length - 1 && isContainer(current) && !Array.isArray(current)) {
      const parent = toPath(tokens.slice(0, -1))
      const key = String(tokens[depth])
      const siblings = result.missing.get(parent) ?? []
      const existing = siblings.find((entry) => entry.key === key)
      if (existing) existing.findings.push(finding)
      else siblings.push({ key, findings: [finding] })
      result.missing.set(parent, siblings)
    } else {
      result.unplaced.push(finding)
      continue
    }
    // Every container above the finding (for a missing property, up to and including its parent).
    for (let length = 0; length < tokens.length; length++) {
      result.ancestors.add(toPath(tokens.slice(0, length)))
    }
  }
  return result
}

function Primitive({ value }: { value: unknown }) {
  if (value === null) return <span className="text-muted-foreground">null</span>
  if (typeof value === 'string') return <span className="break-all text-emerald-700 dark:text-emerald-400">{JSON.stringify(value)}</span>
  if (typeof value === 'number') return <span className="text-sky-700 dark:text-sky-400">{String(value)}</span>
  return <span className="text-violet-700 dark:text-violet-400">{String(value)}</span>
}

function Messages({ findings }: { findings: Finding[] }) {
  return (
    <ul className="mt-0.5 space-y-0.5 font-sans text-[11px] text-red-700 dark:text-red-400">
      {findings.map((finding, position) => (
        <li key={position}>{finding.message}</li>
      ))}
    </ul>
  )
}

const FLAGGED = 'rounded bg-red-50 px-1 ring-1 ring-red-300 ring-inset dark:bg-red-950/40 dark:ring-red-800'

function Node({ label, value, path, depth, findings }: { label: string | null; value: unknown; path: string; depth: number; findings: Index }) {
  const own = findings.at.get(path)
  const name = label === null ? null : <span className="text-foreground">{label}: </span>
  if (!isContainer(value)) {
    return (
      <li data-json-path={path} tabIndex={-1} className={cn('outline-none', own && FLAGGED)}>
        {name}
        <Primitive value={value} />
        {own ? <Messages findings={own} /> : null}
      </li>
    )
  }
  const entries: [string, unknown][] = Array.isArray(value) ? value.map((item, position) => [String(position), item]) : Object.entries(value)
  const missing = findings.missing.get(path) ?? []
  const size = Array.isArray(value) ? `[${value.length}]` : `{${entries.length}}`
  return (
    <li data-json-path={path} tabIndex={-1} className="outline-none">
      <details open={depth < 2 || findings.ancestors.has(path)}>
        <summary className={cn('cursor-pointer select-none', own && FLAGGED)}>
          {name}
          <span className="text-muted-foreground">{size}</span>
          {own ? <Messages findings={own} /> : null}
        </summary>
        <ul className="ml-3 border-l pl-3">
          {entries.map(([key, child]) => (
            <Node key={key} label={key} value={child} path={childPath(path, Array.isArray(value) ? Number(key) : key)} depth={depth + 1} findings={findings} />
          ))}
          {missing.map((entry) => (
            <li key={`missing-${entry.key}`} data-json-path={childPath(path, entry.key)} tabIndex={-1} className={cn('outline-none', FLAGGED)}>
              <span className="text-foreground">{entry.key}: </span>
              <span className="text-red-700 italic dark:text-red-400">missing</span>
              <Messages findings={entry.findings} />
            </li>
          ))}
          {entries.length === 0 && missing.length === 0 ? <li className="text-muted-foreground">empty</li> : null}
        </ul>
      </details>
    </li>
  )
}

function parseJson(body: string, contentType: string | null, truncated: boolean): { value: unknown } | null {
  if (truncated || !contentType || !/[/+]json\b/i.test(contentType.split(';')[0] ?? '')) return null
  try {
    return { value: JSON.parse(body) as unknown }
  } catch {
    return null
  }
}

/**
 * The response body as a JSON tree with the body findings of contract validation marked at their
 * paths (absent required properties are shown as "missing"), or as raw text.
 */
export function BodyInspector({ body, contentType, truncated, checks }: { body: string; contentType: string | null; truncated: boolean; checks: Check[] }) {
  const parsed = useMemo(() => parseJson(body, contentType, truncated), [body, contentType, truncated])
  const findings = useMemo(
    () => checks.filter((check) => check.result === 'FAIL' && check.path !== undefined).map((check) => ({ path: check.path!, message: check.message })),
    [checks],
  )
  const located = useMemo(() => (parsed ? index(parsed.value, findings) : null), [parsed, findings])
  const [raw, setRaw] = useState(false)

  if (!parsed || !located) {
    return <pre className="max-h-96 overflow-auto rounded-md bg-muted p-2 text-xs whitespace-pre-wrap break-all">{body}</pre>
  }
  return (
    <div className="space-y-1">
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground">
          {findings.length === 0 ? 'No body findings.' : `${findings.length} body finding${findings.length === 1 ? '' : 's'} marked.`}
        </span>
        <span className="flex-1" />
        <Button size="sm" variant="ghost" className="h-6 px-2 text-xs" aria-pressed={raw} onClick={() => setRaw(!raw)}>
          {raw ? 'Show tree' : 'Show raw'}
        </Button>
      </div>
      {raw ? (
        <pre className="max-h-96 overflow-auto rounded-md bg-muted p-2 text-xs">{JSON.stringify(parsed.value, null, 2)}</pre>
      ) : (
        <ul aria-label="Response body" className="max-h-96 overflow-auto rounded-md bg-muted p-2 font-mono text-xs">
          <Node label={null} value={parsed.value} path="$" depth={0} findings={located} />
        </ul>
      )}
      {located.unplaced.length > 0 ? (
        <div className="text-xs">
          <p className="text-muted-foreground">Findings that could not be placed in the body:</p>
          <Messages findings={located.unplaced} />
        </div>
      ) : null}
    </div>
  )
}
