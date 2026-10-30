import { Loader2, Sparkles } from 'lucide-react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { formatDateTime } from '@/lib/format'
import { cn } from '@/lib/utils'

import { type Explanation, type Grounding, useAiStatus, useExplainFailure, useExplanation } from './api'
import { useElapsedSeconds } from './useElapsedSeconds'

const GROUNDING: Record<Grounding, { label: string; className: string }> = {
  VERIFIED: { label: 'found in the data', className: 'text-emerald-700 ring-emerald-200 dark:text-emerald-400' },
  UNVERIFIED: { label: 'not found in the data', className: 'text-red-700 ring-red-200 dark:text-red-400' },
  NOT_CHECKABLE: { label: 'not checkable', className: 'text-muted-foreground ring-border' },
}

const TARGETS: Record<string, string> = { TEST: 'Test', SPECIFICATION: 'Specification', API_IMPLEMENTATION: 'API implementation' }

function GroundingBadge({ grounding }: { grounding: Grounding }) {
  return (
    <span className={cn('ml-1 inline-flex rounded px-1 text-[10px] whitespace-nowrap ring-1 ring-inset', GROUNDING[grounding].className)}>
      {GROUNDING[grounding].label}
    </span>
  )
}

function ExplanationView({ explanation }: { explanation: Explanation }) {
  return (
    <div className="space-y-3 text-sm">
      <p className="font-medium">{explanation.summary}</p>
      {explanation.warnings.length > 0 ? (
        <ul className="space-y-0.5 rounded-md bg-amber-50 p-2 text-xs text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
          {explanation.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      ) : null}
      <div>
        <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Observed facts</h4>
        <p className="text-xs text-muted-foreground">What the model read in the recorded result, each checked against that data.</p>
        <ul className="mt-1 list-disc space-y-0.5 pl-5">
          {explanation.observedFacts.map((fact, index) => (
            <li key={index}>
              {fact.statement} <span className="text-xs text-muted-foreground">({fact.source.toLowerCase()})</span>
              <GroundingBadge grounding={fact.grounding} />
            </li>
          ))}
        </ul>
      </div>
      <div>
        <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Likely causes (hypotheses)</h4>
        <ul className="mt-1 space-y-2">
          {explanation.likelyCauses.map((cause, index) => (
            <li key={index} className="rounded-md border p-2">
              <p>
                <Badge variant="outline" className="mr-1.5 text-[10px]">
                  {cause.confidence.toLowerCase()} confidence
                </Badge>
                {cause.hypothesis}
              </p>
              {cause.evidence.length > 0 ? (
                <ul className="mt-1 list-disc pl-5 text-xs text-muted-foreground">
                  {cause.evidence.map((item, position) => (
                    <li key={position}>
                      {item.text}
                      <GroundingBadge grounding={item.grounding} />
                    </li>
                  ))}
                </ul>
              ) : null}
            </li>
          ))}
        </ul>
      </div>
      <div>
        <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Debugging steps</h4>
        <ol className="mt-1 list-decimal space-y-0.5 pl-5">
          {explanation.debuggingSteps.map((step, index) => (
            <li key={index}>{step}</li>
          ))}
        </ol>
      </div>
      {explanation.suggestedChanges.length > 0 ? (
        <div>
          <h4 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Suggested changes</h4>
          <p className="text-xs text-muted-foreground">Proposals for you to review. APIForge applies nothing automatically.</p>
          <ul className="mt-1 space-y-1">
            {explanation.suggestedChanges.map((change, index) => (
              <li key={index}>
                <Badge variant="secondary" className="mr-1.5 text-[10px]">
                  {TARGETS[change.target] ?? change.target}
                </Badge>
                {change.change}
              </li>
            ))}
          </ul>
        </div>
      ) : null}
      <details className="text-xs text-muted-foreground">
        <summary className="cursor-pointer">What the model saw</summary>
        <ul className="mt-1 list-disc pl-5">
          {explanation.inputs.map((input) => (
            <li key={input}>{input}</li>
          ))}
        </ul>
        <p className="mt-1">
          {explanation.sourceCodeProvided
            ? 'Source code was provided.'
            : "It did not see the API's source code, logs, configuration or any secret; statements about the implementation are hypotheses."}
        </p>
      </details>
    </div>
  )
}

/** "Explain failure": asks the local model why this result failed and shows facts and hypotheses apart. */
export function ExplainFailure({ projectId, runId, resultId }: { projectId: string; runId: string; resultId: string }) {
  const status = useAiStatus()
  const stored = useExplanation(projectId, runId, resultId, true)
  const explain = useExplainFailure(projectId, runId, resultId)
  const elapsed = useElapsedSeconds(explain.isPending)
  const ready = status.data?.state === 'READY'
  const explanation = stored.data ?? null

  return (
    <section aria-label="AI explanation" className="space-y-2 rounded-md border border-dashed p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Sparkles className="size-4 text-primary" aria-hidden="true" />
        <h3 className="font-medium">Explain failure</h3>
        {explanation ? (
          <span className="text-xs text-muted-foreground">
            {explanation.model} · {(explanation.latencyMs / 1000).toFixed(1)} s · {formatDateTime(explanation.createdAt)}
          </span>
        ) : null}
        <span className="flex-1" />
        <Button size="sm" variant="outline" disabled={!ready || explain.isPending} onClick={() => explain.mutate()}>
          {explain.isPending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
          {explanation ? 'Explain again' : 'Explain with AI'}
        </Button>
      </div>
      {!ready && status.data ? <p className="text-xs text-amber-800 dark:text-amber-300">AI is not available: {status.data.message}</p> : null}
      {explain.isPending ? (
        <output className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          {status.data?.model ?? 'The model'} is reading the result… {elapsed} s
        </output>
      ) : explain.isError ? (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center gap-2">
            <span className="flex-1">{explain.error.message}</span>
            <Button size="sm" variant="outline" disabled={!ready} onClick={() => explain.mutate()}>
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      ) : null}
      {stored.isError ? <p className="text-xs text-destructive">{stored.error.message}</p> : null}
      {explanation ? (
        <ExplanationView explanation={explanation} />
      ) : !explain.isPending && !stored.isPending ? (
        <p className="text-xs text-muted-foreground">
          The local model reads the failed assertions, the redacted request, the response and the documented schema, and proposes likely causes.
        </p>
      ) : null}
    </section>
  )
}
