import { Sparkles } from 'lucide-react'

import { Badge } from '@/components/ui/badge'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'
import { cn } from '@/lib/utils'

import { type AiState, useAiStatus, useRefreshAiStatus } from './api'

const STATE_LABELS: Record<AiState, string> = {
  READY: 'Ready',
  MODEL_MISSING: 'Model not installed',
  UNAVAILABLE: 'Unavailable',
  DISABLED: 'Off',
}

const STATE_DOTS: Record<AiState, string> = {
  READY: 'bg-emerald-500',
  MODEL_MISSING: 'bg-amber-500',
  UNAVAILABLE: 'bg-muted-foreground',
  DISABLED: 'bg-muted-foreground',
}

/** Header chip: which local model is configured and whether it can be used right now. */
export function AiStatusBadge() {
  const status = useAiStatus()
  const refresh = useRefreshAiStatus()
  const state = status.data?.state
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <button type="button" aria-label={`AI: ${state ? STATE_LABELS[state] : 'checking'}`} className="rounded-md focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none">
          <Badge variant="outline" className="gap-1.5 font-normal">
            <span aria-hidden="true" className={cn('size-2 rounded-full', state ? STATE_DOTS[state] : 'animate-pulse bg-muted-foreground')} />
            <Sparkles className="size-3" aria-hidden="true" />
            <span className="hidden sm:inline">{status.data ? status.data.model : 'AI'}</span>
            <span className="sm:hidden">AI</span>
          </Badge>
        </button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80">
        <DropdownMenuLabel className="space-y-1">
          <span className="block">Local AI: {state ? STATE_LABELS[state] : status.isError ? 'status unknown' : 'checking…'}</span>
          {status.data ? (
            <>
              <span className="block text-xs font-normal text-muted-foreground">
                Model <span className="font-mono">{status.data.model}</span> at <span className="font-mono">{status.data.server}</span>
              </span>
              <span className="block text-xs font-normal text-muted-foreground">{status.data.message}</span>
              {status.data.installedModels.length > 0 ? (
                <span className="block text-xs font-normal text-muted-foreground">Installed: {status.data.installedModels.join(', ')}</span>
              ) : null}
            </>
          ) : status.isError ? (
            <span className="block text-xs font-normal text-destructive">{status.error.message}</span>
          ) : null}
          <span className="block text-xs font-normal text-muted-foreground">
            AI is optional: test generation, runs, contract validation and coverage never depend on it.
          </span>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem
          disabled={refresh.isPending}
          onSelect={(event) => {
            event.preventDefault()
            refresh.mutate()
          }}
        >
          {refresh.isPending ? 'Checking…' : 'Check again'}
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
