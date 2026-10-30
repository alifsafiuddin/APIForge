import { Loader2, Sparkles } from 'lucide-react'
import { useState } from 'react'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { CATEGORY_LABELS, type TestSummary, useReviewTest, useTests } from '@/features/tests/api'

import { type SuggestionReport, useAiStatus, useSuggestTests } from './api'
import { useElapsedSeconds } from './useElapsedSeconds'

function PendingSuggestion({ projectId, test, onOpen }: { projectId: string; test: TestSummary; onOpen: (testId: string) => void }) {
  const review = useReviewTest(projectId, test.id)
  const decide = async (decision: 'APPROVE' | 'REJECT') => {
    try {
      await review.mutateAsync(decision)
      toast.success(decision === 'APPROVE' ? `“${test.name}” accepted; it can run now.` : `“${test.name}” rejected; it will not run.`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The decision could not be saved.')
    }
  }
  return (
    <li className="flex flex-wrap items-center gap-x-2 gap-y-1 px-3 py-2">
      <span className="min-w-0 flex-1">
        <span className="block text-sm">{test.name}</span>
        <span className="block text-xs text-muted-foreground">
          {CATEGORY_LABELS[test.category]} · expects {test.expectedStatuses.join(' or ')}
        </span>
      </span>
      <Button size="sm" disabled={review.isPending} onClick={() => void decide('APPROVE')}>
        Accept
      </Button>
      <Button size="sm" variant="outline" onClick={() => onOpen(test.id)}>
        Edit
      </Button>
      <Button size="sm" variant="ghost" disabled={review.isPending} onClick={() => void decide('REJECT')}>
        Reject
      </Button>
    </li>
  )
}

function ReportSummary({ report }: { report: SuggestionReport }) {
  return (
    <div className="space-y-1.5 text-sm" aria-live="polite">
      <p>
        {report.created.length === 0 ? 'No new suggestion was added' : `${report.created.length} suggestion${report.created.length === 1 ? '' : 's'} added for review`}{' '}
        <span className="text-muted-foreground">
          ({report.model}, {(report.latencyMs / 1000).toFixed(1)} s{report.attempts > 1 ? `, ${report.attempts} attempts` : ''})
        </span>
        .
      </p>
      {report.possibleDuplicates.length > 0 ? (
        <ul className="space-y-0.5 text-xs text-amber-800 dark:text-amber-300">
          {report.possibleDuplicates.map((item) => (
            <li key={item.name}>
              “{item.name}” may duplicate “{item.similarTo}” (differs only in {item.difference}).
            </li>
          ))}
        </ul>
      ) : null}
      {report.discarded.length > 0 ? (
        <details className="text-xs">
          <summary className="cursor-pointer text-muted-foreground">
            {report.discarded.length} suggestion{report.discarded.length === 1 ? ' was' : 's were'} discarded
          </summary>
          <ul className="mt-1 space-y-1">
            {report.discarded.map((item, index) => (
              <li key={index}>
                <span className="font-medium">{item.name}</span> — {item.reason}
              </li>
            ))}
          </ul>
        </details>
      ) : null}
    </div>
  )
}

/**
 * Asks the local model for tests the existing ones miss. Suggestions are request data only; they
 * wait here for review and cannot run until accepted. Rendered with the operation as key, so its
 * state starts fresh for each endpoint.
 */
export function AiSuggestionsPanel({ projectId, operationKey, onOpen }: { projectId: string; operationKey: string; onOpen: (testId: string) => void }) {
  const status = useAiStatus()
  const suggest = useSuggestTests(projectId)
  const pending = useTests(projectId, { operationKey, origin: 'AI_SUGGESTED', reviewStatus: 'PENDING_REVIEW' })
  const elapsed = useElapsedSeconds(suggest.isPending)
  const [report, setReport] = useState<SuggestionReport | null>(null)
  const ready = status.data?.state === 'READY'

  const run = async () => {
    setReport(null)
    try {
      setReport(await suggest.mutateAsync(operationKey))
    } catch {
      // Shown below from the mutation state.
    }
  }

  const waiting = pending.data?.content ?? []
  return (
    <section aria-label="AI suggestions">
      <Card className="gap-3 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Sparkles className="size-4 text-primary" aria-hidden="true" />
        <h3 className="font-medium">AI suggestions</h3>
        {status.data ? <span className="font-mono text-xs text-muted-foreground">{status.data.model}</span> : null}
        <span className="flex-1" />
        <Button size="sm" variant="outline" disabled={!ready || suggest.isPending} onClick={() => void run()}>
          {suggest.isPending ? <Loader2 className="size-4 animate-spin" aria-hidden="true" /> : null}
          Suggest tests
        </Button>
      </div>
      <p className="text-xs text-muted-foreground">
        The local model proposes scenarios the existing tests do not cover. Suggestions are request data only, are checked like a manual edit, and
        wait for your review: nothing runs until you accept it.
      </p>
      {!ready && status.data ? <p className="text-xs text-amber-800 dark:text-amber-300">AI is not available: {status.data.message}</p> : null}
      {suggest.isPending ? (
        <output className="flex items-center gap-2 text-sm text-muted-foreground">
          <Loader2 className="size-4 animate-spin" aria-hidden="true" />
          Asking {status.data?.model ?? 'the model'} for suggestions… {elapsed} s. A local model can take a minute.
        </output>
      ) : suggest.isError ? (
        <Alert variant="destructive">
          <AlertDescription className="flex flex-wrap items-center gap-2">
            <span className="flex-1">{suggest.error.message}</span>
            <Button size="sm" variant="outline" disabled={!ready} onClick={() => void run()}>
              Try again
            </Button>
          </AlertDescription>
        </Alert>
      ) : report ? (
        <ReportSummary report={report} />
      ) : null}
      {waiting.length > 0 ? (
        <div className="space-y-1">
          <p className="text-xs font-medium">
            Waiting for review <Badge variant="secondary">{waiting.length}</Badge>
          </p>
          <ul aria-label="Suggestions waiting for review" className="divide-y rounded-md border">
            {waiting.map((test) => (
              <PendingSuggestion key={test.id} projectId={projectId} test={test} onOpen={onOpen} />
            ))}
          </ul>
        </div>
      ) : null}
      </Card>
    </section>
  )
}
