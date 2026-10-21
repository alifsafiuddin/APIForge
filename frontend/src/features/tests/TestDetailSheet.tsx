import { useState } from 'react'
import { toast } from 'sonner'

import { ApiError } from '@/api/client'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/components/ui/sheet'
import { Skeleton } from '@/components/ui/skeleton'
import { Textarea } from '@/components/ui/textarea'
import { MethodBadge, StatusBadge } from '@/features/specs/components/MethodBadge'
import { formatDateTime } from '@/lib/format'

import {
  CATEGORY_LABELS,
  requestSchema,
  type TestDetail,
  useChangeTest,
  useEditTest,
  useReviewTest,
  useSuites,
  useTest,
} from './api'

const AUTH_LABELS = {
  ENVIRONMENT: 'Credentials from the environment',
  NONE: 'No credentials',
  INVALID: 'Deliberately invalid credentials',
} as const

/** Side panel with everything about one test: why it exists, what it sends and expects, and controls. */
export function TestDetailSheet({
  projectId,
  testId,
  onClose,
}: {
  projectId: string
  testId: string | undefined
  onClose: () => void
}) {
  const test = useTest(projectId, testId)
  const [editing, setEditing] = useState(false)

  return (
    <Sheet
      open={testId !== undefined}
      onOpenChange={(open) => {
        if (!open) {
          setEditing(false)
          onClose()
        }
      }}
    >
      <SheetContent className="w-full overflow-y-auto data-[side=right]:sm:max-w-2xl">
        {test.isPending ? (
          <div className="space-y-3 p-4" aria-label="Loading test">
            <Skeleton className="h-6 w-2/3" />
            <Skeleton className="h-40 w-full" />
          </div>
        ) : test.isError ? (
          <div className="p-4">
            <SheetTitle>Test unavailable</SheetTitle>
            <p className="mt-2 text-sm text-destructive">{test.error.message}</p>
          </div>
        ) : editing ? (
          <EditTestForm projectId={projectId} test={test.data} onDone={() => setEditing(false)} />
        ) : (
          <TestView projectId={projectId} test={test.data} onEdit={() => setEditing(true)} />
        )}
      </SheetContent>
    </Sheet>
  )
}

function TestView({ projectId, test, onEdit }: { projectId: string; test: TestDetail; onEdit: () => void }) {
  const change = useChangeTest(projectId)
  const review = useReviewTest(projectId, test.id)
  const suites = useSuites(projectId)

  const run = async (action: () => Promise<unknown>, success: string) => {
    try {
      await action()
      toast.success(success)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The change could not be saved.')
    }
  }

  const request = test.request
  return (
    <>
      <SheetHeader className="pr-10">
        <SheetTitle>{test.name}</SheetTitle>
        <SheetDescription className="flex flex-wrap items-center gap-1.5">
          <MethodBadge method={test.method} />
          <span className="font-mono text-xs">{test.path}</span>
        </SheetDescription>
        <div className="flex flex-wrap gap-1.5">
          <Badge variant="secondary">{CATEGORY_LABELS[test.category]}</Badge>
          <Badge variant="outline">{test.origin === 'DETERMINISTIC' ? 'Deterministic' : test.origin === 'AI_SUGGESTED' ? 'AI-suggested' : 'Manual'}</Badge>
          <Badge variant="outline">{test.riskLevel.replace('_', ' ').toLowerCase()}</Badge>
          {test.userModified ? <Badge variant="outline">Edited</Badge> : null}
          {!test.executable ? <Badge variant="destructive">Will not run</Badge> : null}
        </div>
      </SheetHeader>

      <div className="space-y-5 px-4 pb-6 text-sm">
        <section aria-labelledby="why">
          <h3 id="why" className="mb-1 font-medium">Why this test exists</h3>
          <p className="text-muted-foreground">{test.rationale ?? 'No rationale recorded.'}</p>
        </section>

        <section aria-labelledby="expected">
          <h3 id="expected" className="mb-1 font-medium">Expected outcome</h3>
          <div className="mb-1 flex flex-wrap gap-1.5">
            {test.expectations.statuses.map((status) => (
              <StatusBadge key={status} status={status} />
            ))}
          </div>
          <p className="text-muted-foreground">{test.expectations.summary}</p>
          <p className="mt-1 text-xs text-muted-foreground">
            Response schema validation: {test.expectations.validateResponseSchema ? 'on' : 'off'}
          </p>
        </section>

        <section aria-labelledby="request" className="space-y-2">
          <h3 id="request" className="font-medium">Request</h3>
          <KeyValues title="Path parameters" values={request.pathParams} />
          <KeyValues title="Query parameters" values={request.query} />
          <KeyValues title="Headers" values={request.headers} />
          <div>
            <p className="text-xs text-muted-foreground">Body</p>
            {request.body.mode === 'NONE' ? (
              <p>No body</p>
            ) : (
              <>
                <p className="font-mono text-xs">Content-Type: {request.body.contentType}</p>
                <pre className="mt-1 max-h-72 overflow-auto rounded-md bg-muted p-2 text-xs">
                  {request.body.mode === 'RAW' ? String(request.body.value) : JSON.stringify(request.body.value, null, 2)}
                </pre>
              </>
            )}
          </div>
          <p>
            <span className="text-xs text-muted-foreground">Authentication: </span>
            {AUTH_LABELS[request.auth]}
          </p>
        </section>

        <section aria-labelledby="provenance">
          <h3 id="provenance" className="mb-1 font-medium">Provenance</h3>
          <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs">
            <dt className="text-muted-foreground">Scenario</dt>
            <dd className="font-mono break-all">{test.scenarioKey ?? '—'}</dd>
            <dt className="text-muted-foreground">Source</dt>
            <dd className="font-mono break-all">{test.specLocation ?? '—'}</dd>
            <dt className="text-muted-foreground">Generator</dt>
            <dd>{test.generatorVersion ? `v${test.generatorVersion}` : '—'}</dd>
            <dt className="text-muted-foreground">Updated</dt>
            <dd>{formatDateTime(test.updatedAt)}</dd>
          </dl>
        </section>

        <section aria-labelledby="manage" className="space-y-3 border-t pt-4">
          <h3 id="manage" className="font-medium">Manage</h3>
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              role="switch"
              aria-checked={test.enabled}
              className="size-4"
              checked={test.enabled}
              disabled={change.isPending}
              onChange={() =>
                void run(
                  () => change.mutateAsync({ testId: test.id, enabled: !test.enabled, version: test.version }),
                  test.enabled ? 'Test disabled.' : 'Test enabled.',
                )
              }
            />
            Enabled (disabled tests are kept but never run)
          </label>
          <div className="flex flex-wrap items-center gap-2">
            <Label htmlFor="move-suite">Suite</Label>
            <select
              id="move-suite"
              className="h-8 rounded-lg border border-input bg-background px-2.5 text-sm"
              value={test.suiteId}
              disabled={change.isPending}
              onChange={(event) =>
                void run(
                  () => change.mutateAsync({ testId: test.id, suiteId: event.target.value, version: test.version }),
                  'Test moved.',
                )
              }
            >
              {suites.data?.map((suite) => (
                <option key={suite.id} value={suite.id}>
                  {suite.name}
                </option>
              )) ?? <option value={test.suiteId}>…</option>}
            </select>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <span>
              Review: <strong>{test.reviewStatus === 'APPROVED' ? 'approved' : test.reviewStatus === 'REJECTED' ? 'rejected' : 'pending'}</strong>
            </span>
            {test.reviewStatus !== 'APPROVED' ? (
              <Button size="sm" variant="outline" disabled={review.isPending}
                onClick={() => void run(() => review.mutateAsync('APPROVE'), 'Test approved.')}>
                Approve
              </Button>
            ) : null}
            {test.reviewStatus !== 'REJECTED' ? (
              <Button size="sm" variant="outline" disabled={review.isPending}
                onClick={() => void run(() => review.mutateAsync('REJECT'), 'Test rejected; it will not run.')}>
                Reject
              </Button>
            ) : null}
          </div>
          <Button onClick={onEdit}>Edit test</Button>
        </section>
      </div>
    </>
  )
}

function KeyValues({ title, values }: { title: string; values: Record<string, unknown> }) {
  const entries = Object.entries(values)
  if (entries.length === 0) {
    return null
  }
  return (
    <div>
      <p className="text-xs text-muted-foreground">{title}</p>
      <ul className="font-mono text-xs">
        {entries.map(([name, value]) => (
          <li key={name} className="break-all">
            {name} = {typeof value === 'string' ? value : JSON.stringify(value)}
          </li>
        ))}
      </ul>
    </div>
  )
}

function EditTestForm({ projectId, test, onDone }: { projectId: string; test: TestDetail; onDone: () => void }) {
  const edit = useEditTest(projectId, test.id)
  const [name, setName] = useState(test.name)
  const [statuses, setStatuses] = useState(test.expectations.statuses.join(', '))
  const [summary, setSummary] = useState(test.expectations.summary)
  const [validateSchema, setValidateSchema] = useState(test.expectations.validateResponseSchema)
  const [rationale, setRationale] = useState(test.rationale ?? '')
  const [requestText, setRequestText] = useState(JSON.stringify(test.request, null, 2))
  const [errors, setErrors] = useState<string[]>([])

  const save = async (event: React.FormEvent) => {
    event.preventDefault()
    let parsed: unknown
    try {
      parsed = JSON.parse(requestText)
    } catch {
      setErrors(['Request: not valid JSON.'])
      return
    }
    const request = requestSchema.safeParse(parsed)
    if (!request.success) {
      setErrors(request.error.issues.map((issue) => `Request ${issue.path.join('.') || '(root)'}: ${issue.message}`))
      return
    }
    const statusList = statuses.split(',').map((status) => status.trim()).filter(Boolean)
    if (name.trim() === '' || statusList.length === 0) {
      setErrors(['Enter a name and at least one expected status.'])
      return
    }
    try {
      await edit.mutateAsync({
        name: name.trim(),
        request: request.data,
        expectations: { statuses: statusList, summary, validateResponseSchema: validateSchema },
        rationale: rationale.trim() === '' ? null : rationale,
        version: test.version,
      })
      toast.success('Test saved. Regeneration will leave it as you edited it.')
      onDone()
    } catch (error) {
      if (error instanceof ApiError && error.fieldErrors.length > 0) {
        setErrors(error.fieldErrors.map((fieldError) => `${fieldError.field}: ${fieldError.message}`))
      } else {
        setErrors([error instanceof Error ? error.message : 'The test could not be saved.'])
      }
    }
  }

  return (
    <form onSubmit={(event) => void save(event)} className="space-y-4 p-4 text-sm" noValidate>
      <SheetHeader className="p-0 pr-10">
        <SheetTitle>Edit test</SheetTitle>
        <SheetDescription>
          Edited tests are marked as yours: regenerating tests never overwrites them. Host, base URL and credentials
          always come from the environment.
        </SheetDescription>
      </SheetHeader>
      {errors.length > 0 ? (
        <Alert variant="destructive">
          <AlertDescription>
            <ul className="list-disc pl-4">
              {errors.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          </AlertDescription>
        </Alert>
      ) : null}
      <div className="space-y-1.5">
        <Label htmlFor="test-name">Name</Label>
        <Input id="test-name" value={name} maxLength={200} onChange={(event) => setName(event.target.value)} />
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="test-statuses">Expected statuses</Label>
        <Input
          id="test-statuses"
          value={statuses}
          aria-describedby="test-statuses-hint"
          onChange={(event) => setStatuses(event.target.value)}
        />
        <p id="test-statuses-hint" className="text-xs text-muted-foreground">
          Comma-separated codes or classes, e.g. 400, 422 or 4XX.
        </p>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="test-summary">Expected outcome (in words)</Label>
        <Textarea id="test-summary" value={summary} maxLength={1000} onChange={(event) => setSummary(event.target.value)} />
      </div>
      <label className="flex items-center gap-2">
        <input
          type="checkbox"
          className="size-4"
          checked={validateSchema}
          onChange={(event) => setValidateSchema(event.target.checked)}
        />
        Validate the response body against the documented schema
      </label>
      <div className="space-y-1.5">
        <Label htmlFor="test-request">Request (JSON)</Label>
        <Textarea
          id="test-request"
          className="min-h-72 font-mono text-xs"
          spellCheck={false}
          value={requestText}
          aria-describedby="test-request-hint"
          onChange={(event) => setRequestText(event.target.value)}
        />
        <p id="test-request-hint" className="text-xs text-muted-foreground">
          pathParams, query and headers are name → value maps; body.mode is NONE, JSON or RAW; auth is ENVIRONMENT, NONE
          or INVALID.
        </p>
      </div>
      <div className="space-y-1.5">
        <Label htmlFor="test-rationale">Rationale</Label>
        <Textarea id="test-rationale" value={rationale} maxLength={4000} onChange={(event) => setRationale(event.target.value)} />
      </div>
      <div className="flex gap-2">
        <Button type="submit" disabled={edit.isPending}>
          {edit.isPending ? 'Saving…' : 'Save'}
        </Button>
        <Button type="button" variant="outline" onClick={onDone}>
          Cancel
        </Button>
      </div>
    </form>
  )
}
