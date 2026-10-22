import { Send } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router'

import { ApiError } from '@/api/client'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'
import { useEnvironments } from '@/features/environments/api'

import { type AdHocInput, type ResultDetail, useAdHocRequest } from './api'
import { ResultView } from './ResultView'

const SELECT = 'h-9 rounded-lg border border-input bg-background px-2.5 text-sm'
const METHODS = ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'HEAD', 'OPTIONS']

/** JSON.parse with an error that names the field, instead of the parser's bare message. */
function parseJson(label: string, text: string): unknown {
  try {
    return JSON.parse(text) as unknown
  } catch (error) {
    throw new Error(`${label} is not valid JSON${error instanceof Error ? ` (${error.message})` : ''}.`, { cause: error })
  }
}

function parseObject(label: string, text: string): Record<string, unknown> {
  if (text.trim() === '') return {}
  const value = parseJson(label, text)
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new Error(`${label} must be a JSON object.`)
  }
  return value as Record<string, unknown>
}

/** Sends one request through the same guarded engine as test runs (ad hoc execution). */
export function ConsolePage() {
  const { projectId = '' } = useParams()
  const environments = useEnvironments(projectId)
  const send = useAdHocRequest(projectId)
  const [environmentId, setEnvironmentId] = useState('')
  const [method, setMethod] = useState('GET')
  const [path, setPath] = useState('/health')
  const [query, setQuery] = useState('')
  const [headers, setHeaders] = useState('')
  const [bodyMode, setBodyMode] = useState<'NONE' | 'JSON' | 'RAW'>('NONE')
  const [contentType, setContentType] = useState('application/json')
  const [body, setBody] = useState('')
  const [auth, setAuth] = useState<'ENVIRONMENT' | 'NONE' | 'INVALID'>('ENVIRONMENT')
  const [expected, setExpected] = useState('2XX')
  const [errors, setErrors] = useState<string[]>([])
  const [result, setResult] = useState<ResultDetail | null>(null)
  const chosen = environmentId || environments.data?.[0]?.id || ''

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    let input: AdHocInput
    try {
      input = {
        environmentId: chosen,
        method,
        path,
        request: {
          pathParams: {},
          query: parseObject('Query', query),
          headers: parseObject('Headers', headers) as Record<string, string>,
          body: bodyMode === 'NONE' ? { mode: 'NONE' } : { mode: bodyMode, contentType, value: bodyMode === 'JSON' ? parseJson('Body', body || 'null') : body },
          auth,
        },
        expectedStatuses: expected.split(',').map((status) => status.trim()).filter(Boolean),
      }
    } catch (error) {
      setErrors([error instanceof Error ? error.message : 'Invalid input.'])
      return
    }
    try {
      setErrors([])
      setResult(await send.mutateAsync(input))
    } catch (error) {
      setErrors(
        error instanceof ApiError && error.fieldErrors.length > 0
          ? error.fieldErrors.map((fieldError) => `${fieldError.field}: ${fieldError.message}`)
          : [error instanceof Error ? error.message : 'The request could not be sent.'],
      )
    }
  }

  if (environments.data && environments.data.length === 0) {
    return (
      <Card className="p-6 text-sm">
        Create an environment on the{' '}
        <Link className="underline" to={`/projects/${projectId}/environments`}>
          Environments
        </Link>{' '}
        tab to send requests.
      </Card>
    )
  }

  return (
    <div className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
      <Card className="p-4">
        <form onSubmit={(event) => void submit(event)} className="space-y-3 text-sm" noValidate>
          <div className="space-y-1.5">
            <Label htmlFor="console-environment">Environment</Label>
            <select id="console-environment" className={`${SELECT} w-full`} value={chosen} onChange={(event) => setEnvironmentId(event.target.value)}>
              {environments.data?.map((environment) => (
                <option key={environment.id} value={environment.id}>
                  {environment.name} — {environment.baseUrl}
                </option>
              ))}
            </select>
          </div>
          <div className="flex gap-2">
            <select aria-label="Method" className={SELECT} value={method} onChange={(event) => setMethod(event.target.value)}>
              {METHODS.map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
            <Input aria-label="Path" className="font-mono" value={path} onChange={(event) => setPath(event.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="console-query">Query parameters (JSON object)</Label>
            <Textarea id="console-query" className="min-h-14 font-mono text-xs" placeholder='{"limit": 10}' value={query} onChange={(event) => setQuery(event.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="console-headers">Headers (JSON object)</Label>
            <Textarea id="console-headers" className="min-h-14 font-mono text-xs" placeholder='{"X-Request-Id": "{{REQUEST_ID}}"}' value={headers} onChange={(event) => setHeaders(event.target.value)} />
          </div>
          <div className="flex flex-wrap gap-2">
            <select aria-label="Body mode" className={SELECT} value={bodyMode} onChange={(event) => setBodyMode(event.target.value as 'NONE' | 'JSON' | 'RAW')}>
              <option value="NONE">No body</option>
              <option value="JSON">JSON body</option>
              <option value="RAW">Raw body</option>
            </select>
            {bodyMode !== 'NONE' ? <Input aria-label="Content type" className="w-56 font-mono" value={contentType} onChange={(event) => setContentType(event.target.value)} /> : null}
          </div>
          {bodyMode !== 'NONE' ? <Textarea aria-label="Body" className="min-h-28 font-mono text-xs" value={body} onChange={(event) => setBody(event.target.value)} /> : null}
          <div className="flex flex-wrap gap-2">
            <select aria-label="Authentication" className={SELECT} value={auth} onChange={(event) => setAuth(event.target.value as 'ENVIRONMENT' | 'NONE' | 'INVALID')}>
              <option value="ENVIRONMENT">Environment credentials</option>
              <option value="NONE">No credentials</option>
              <option value="INVALID">Invalid credentials</option>
            </select>
            <Input aria-label="Expected statuses" className="w-40 font-mono" value={expected} onChange={(event) => setExpected(event.target.value)} />
          </div>
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
          <Button type="submit" disabled={!chosen || send.isPending}>
            <Send aria-hidden="true" />
            {send.isPending ? 'Sending…' : 'Send'}
          </Button>
        </form>
      </Card>
      <Card className="p-4" aria-label="Result">
        <h2 className="sr-only">Result</h2>
        {result ? <ResultView result={result} projectId={projectId} /> : <p className="text-sm text-muted-foreground">The result appears here. It is also kept in Runs.</p>}
      </Card>
    </div>
  )
}
