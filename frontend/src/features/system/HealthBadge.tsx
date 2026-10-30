import { Badge } from '@/components/ui/badge'

import type { HealthStatus } from './api'

export type DisplayStatus = HealthStatus | 'UNREACHABLE' | 'CHECKING'

const LABELS: Record<DisplayStatus, string> = {
  UP: 'Operational',
  DOWN: 'Down',
  OUT_OF_SERVICE: 'Out of service',
  UNKNOWN: 'Unknown',
  UNREACHABLE: 'Unreachable',
  CHECKING: 'Checking…',
}

const DOT_CLASSES: Record<DisplayStatus, string> = {
  UP: 'bg-emerald-500',
  DOWN: 'bg-red-500',
  OUT_OF_SERVICE: 'bg-amber-500',
  UNKNOWN: 'bg-muted-foreground',
  UNREACHABLE: 'bg-red-500',
  CHECKING: 'bg-muted-foreground animate-pulse',
}

/** `compact`: on narrow screens only the coloured dot is visible; the label stays available to screen readers. */
export function HealthBadge({ status, compact = false }: { status: DisplayStatus; compact?: boolean }) {
  return (
    <Badge variant="outline" className="gap-1.5 font-normal" title={LABELS[status]}>
      <span aria-hidden="true" className={`size-2 rounded-full ${DOT_CLASSES[status]}`} />
      <span className={compact ? 'sr-only sm:not-sr-only' : undefined}>{LABELS[status]}</span>
    </Badge>
  )
}
