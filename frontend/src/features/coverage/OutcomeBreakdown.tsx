import { cn } from '@/lib/utils'

import type { OutcomeCounts } from './api'

const OUTCOME_PARTS: [keyof OutcomeCounts, string, string][] = [
  ['passed', 'passed', 'bg-emerald-500'],
  ['failed', 'failed', 'bg-red-500'],
  ['error', 'errors', 'bg-orange-500'],
  ['inconclusive', 'inconclusive', 'bg-amber-400'],
  ['skipped', 'skipped', 'bg-slate-400'],
  ['notRun', 'not run', 'bg-muted-foreground/25'],
]

export function OutcomeBreakdown({ outcomes, compact = false }: { outcomes: OutcomeCounts; compact?: boolean }) {
  const total = Math.max(outcomes.tests, 1)
  return (
    <div className="space-y-1.5">
      <div className={cn('flex w-full overflow-hidden rounded bg-muted', compact ? 'h-1.5' : 'h-2.5')} aria-hidden="true">
        {OUTCOME_PARTS.map(([key, , color]) => (
          <div key={key} className={color} style={{ width: `${(outcomes[key] / total) * 100}%` }} />
        ))}
      </div>
      {compact ? null : (
        <p className="flex flex-wrap gap-x-3 gap-y-1 text-xs text-muted-foreground">
          {OUTCOME_PARTS.map(([key, label, color]) => (
            <span key={key} className="inline-flex items-center gap-1">
              <span className={cn('inline-block size-2 rounded-sm', color)} aria-hidden="true" />
              {outcomes[key]} {label}
            </span>
          ))}
        </p>
      )}
    </div>
  )
}
