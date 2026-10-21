import { cn } from '@/lib/utils'

const METHOD_STYLES: Record<string, string> = {
  GET: 'bg-sky-100 text-sky-800 ring-sky-200',
  POST: 'bg-emerald-100 text-emerald-800 ring-emerald-200',
  PUT: 'bg-amber-100 text-amber-900 ring-amber-200',
  PATCH: 'bg-violet-100 text-violet-800 ring-violet-200',
  DELETE: 'bg-red-100 text-red-800 ring-red-200',
}

/** Fixed-width HTTP method label; color is supplementary, the text is always shown. */
export function MethodBadge({ method, className }: { method: string; className?: string }) {
  const upper = method.toUpperCase()
  return (
    <span
      className={cn(
        'inline-flex w-16 shrink-0 justify-center rounded px-1.5 py-0.5 font-mono text-[0.7rem] font-semibold ring-1 ring-inset',
        METHOD_STYLES[upper] ?? 'bg-muted text-foreground ring-border',
        className,
      )}
    >
      {upper}
    </span>
  )
}

/** HTTP status label colored by class (2xx success, 4xx client error, ...). */
export function StatusBadge({ status }: { status: string }) {
  const first = status.charAt(0)
  const style =
    first === '2'
      ? 'bg-emerald-100 text-emerald-800 ring-emerald-200'
      : first === '3'
        ? 'bg-sky-100 text-sky-800 ring-sky-200'
        : first === '4'
          ? 'bg-amber-100 text-amber-900 ring-amber-200'
          : first === '5'
            ? 'bg-red-100 text-red-800 ring-red-200'
            : 'bg-muted text-foreground ring-border'
  return (
    <span className={cn('inline-flex min-w-12 justify-center rounded px-1.5 py-0.5 font-mono text-xs font-semibold ring-1 ring-inset', style)}>
      {status}
    </span>
  )
}
