import { useState } from 'react'
import { toast } from 'sonner'

import { ApiError } from '@/api/client'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Textarea } from '@/components/ui/textarea'

import {
  AUTH_LABELS,
  AUTH_TYPES,
  type AuthType,
  type EnvironmentDetail,
  type EnvironmentInput,
  POLICIES,
  POLICY_LABELS,
  type Policy,
  useSaveEnvironment,
} from './api'

const SELECT = 'h-9 w-full rounded-lg border border-input bg-background px-2.5 text-sm'

function headersText(headers: Record<string, string>) {
  return Object.entries(headers)
    .map(([name, value]) => `${name}: ${value}`)
    .join('\n')
}

function parseHeaders(text: string): Record<string, string> | string {
  const headers: Record<string, string> = {}
  for (const line of text.split('\n')) {
    if (line.trim() === '') continue
    const colon = line.indexOf(':')
    if (colon <= 0) return `"${line.trim()}" is not a "Name: value" line.`
    headers[line.slice(0, colon).trim()] = line.slice(colon + 1).trim()
  }
  return headers
}

/** Create or edit an environment. Credentials are never entered here: auth settings name variables. */
export function EnvironmentFormDialog({
  projectId,
  environment,
  open,
  onOpenChange,
  firstEnvironment,
}: {
  projectId: string
  environment: EnvironmentDetail | null
  open: boolean
  onOpenChange: (open: boolean) => void
  firstEnvironment: boolean
}) {
  const save = useSaveEnvironment(projectId)
  const config = environment?.auth.config ?? {}
  const text = (key: string) => (typeof config[key] === 'string' ? (config[key] as string) : '')
  const [name, setName] = useState(environment?.name ?? '')
  const [baseUrl, setBaseUrl] = useState(environment?.baseUrl ?? '')
  const [timeoutMs, setTimeoutMs] = useState(String(environment?.timeoutMs ?? 15000))
  const [followRedirects, setFollowRedirects] = useState(environment?.followRedirects ?? false)
  const [policy, setPolicy] = useState<Policy>(environment?.executionPolicy ?? 'READ_ONLY')
  const [confirmed, setConfirmed] = useState(false)
  const [authType, setAuthType] = useState<AuthType>(environment?.auth.type ?? 'NONE')
  const [tokenVariable, setTokenVariable] = useState(text('tokenVariable') || 'TOKEN')
  const [usernameVariable, setUsernameVariable] = useState(text('usernameVariable') || 'USERNAME')
  const [passwordVariable, setPasswordVariable] = useState(text('passwordVariable') || 'PASSWORD')
  const [headerName, setHeaderName] = useState(text('headerName') || 'X-API-Key')
  const [parameterName, setParameterName] = useState(text('parameterName') || 'api_key')
  const [valueVariable, setValueVariable] = useState(text('valueVariable') || 'API_KEY')
  const [headers, setHeaders] = useState(headersText(environment?.defaultHeaders ?? {}))
  const [errors, setErrors] = useState<string[]>([])

  const submit = async (event: React.FormEvent) => {
    event.preventDefault()
    const parsedHeaders = parseHeaders(headers)
    if (typeof parsedHeaders === 'string') {
      setErrors([`Default headers: ${parsedHeaders}`])
      return
    }
    const input: EnvironmentInput = {
      name,
      baseUrl,
      timeoutMs: Number(timeoutMs),
      followRedirects,
      executionPolicy: policy,
      authorizationConfirmed: confirmed,
      auth: { type: authType, tokenVariable, usernameVariable, passwordVariable, headerName, parameterName, valueVariable },
      defaultHeaders: parsedHeaders,
      ...(environment ? { version: environment.version } : {}),
    }
    try {
      await save.mutateAsync({ ...(environment ? { id: environment.id } : {}), input })
      toast.success(environment ? 'Environment saved.' : 'Environment created.')
      onOpenChange(false)
    } catch (error) {
      setErrors(
        error instanceof ApiError && error.fieldErrors.length > 0
          ? error.fieldErrors.map((fieldError) => `${fieldError.field}: ${fieldError.message}`)
          : [error instanceof Error ? error.message : 'The environment could not be saved.'],
      )
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>{environment ? 'Edit environment' : 'New environment'}</DialogTitle>
          <DialogDescription>
            Where tests are sent. Credentials live in secret variables; settings below only name them.
          </DialogDescription>
        </DialogHeader>
        {firstEnvironment && !environment ? (
          <Alert>
            <AlertDescription>
              Only send requests to APIs you own or are explicitly authorized to test. Private, loopback and cloud metadata
              addresses are blocked; APIForge is not a network scanner.
            </AlertDescription>
          </Alert>
        ) : null}
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
        <form id="environment-form" onSubmit={(event) => void submit(event)} className="space-y-3 text-sm" noValidate>
          <div className="space-y-1.5">
            <Label htmlFor="env-name">Name</Label>
            <Input id="env-name" value={name} maxLength={100} onChange={(event) => setName(event.target.value)} />
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="env-url">Base URL</Label>
            <Input
              id="env-url"
              value={baseUrl}
              placeholder="https://staging.example.com/api"
              onChange={(event) => setBaseUrl(event.target.value)}
            />
          </div>
          <div className="grid gap-3 sm:grid-cols-2">
            <div className="space-y-1.5">
              <Label htmlFor="env-timeout">Read timeout (ms)</Label>
              <Input id="env-timeout" inputMode="numeric" value={timeoutMs} onChange={(event) => setTimeoutMs(event.target.value)} />
            </div>
            <label className="flex items-end gap-2 pb-2">
              <input type="checkbox" className="size-4" checked={followRedirects} onChange={(event) => setFollowRedirects(event.target.checked)} />
              Follow redirects (each one is checked again)
            </label>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="env-policy">Execution policy</Label>
            <select id="env-policy" className={SELECT} value={policy} onChange={(event) => setPolicy(event.target.value as Policy)}>
              {POLICIES.map((value) => (
                <option key={value} value={value}>
                  {POLICY_LABELS[value]}
                </option>
              ))}
            </select>
            {policy !== 'READ_ONLY' ? (
              <label className="flex items-start gap-2 rounded-lg border border-amber-300 bg-amber-50 p-2 text-amber-900">
                <input type="checkbox" className="mt-0.5 size-4" checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)} />
                I confirm this is a non-production system that I am authorized to test, and that tests may create, change or
                delete data there.
              </label>
            ) : null}
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="env-auth">Authentication</Label>
            <select id="env-auth" className={SELECT} value={authType} onChange={(event) => setAuthType(event.target.value as AuthType)}>
              {AUTH_TYPES.map((value) => (
                <option key={value} value={value}>
                  {AUTH_LABELS[value]}
                </option>
              ))}
            </select>
            <div className="grid gap-2 sm:grid-cols-2">
              {authType === 'BEARER' ? (
                <Field id="auth-token" label="Token variable" value={tokenVariable} onChange={setTokenVariable} />
              ) : null}
              {authType === 'BASIC' ? (
                <>
                  <Field id="auth-user" label="Username variable" value={usernameVariable} onChange={setUsernameVariable} />
                  <Field id="auth-pass" label="Password variable" value={passwordVariable} onChange={setPasswordVariable} />
                </>
              ) : null}
              {authType === 'API_KEY_HEADER' ? (
                <>
                  <Field id="auth-header" label="Header name" value={headerName} onChange={setHeaderName} />
                  <Field id="auth-value" label="Key variable" value={valueVariable} onChange={setValueVariable} />
                </>
              ) : null}
              {authType === 'API_KEY_QUERY' ? (
                <>
                  <Field id="auth-param" label="Query parameter" value={parameterName} onChange={setParameterName} />
                  <Field id="auth-value" label="Key variable" value={valueVariable} onChange={setValueVariable} />
                </>
              ) : null}
            </div>
          </div>
          <div className="space-y-1.5">
            <Label htmlFor="env-headers">Default headers</Label>
            <Textarea
              id="env-headers"
              className="font-mono text-xs"
              placeholder={'Accept: application/json\nX-Tenant: {{TENANT}}'}
              value={headers}
              onChange={(event) => setHeaders(event.target.value)}
            />
            <p className="text-xs text-muted-foreground">One &quot;Name: value&quot; per line; {'{{VARIABLE}}'} references are resolved when sending.</p>
          </div>
        </form>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button type="submit" form="environment-form" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : 'Save'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Field({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="space-y-1">
      <Label htmlFor={id} className="text-xs">
        {label}
      </Label>
      <Input id={id} className="font-mono" value={value} onChange={(event) => onChange(event.target.value)} />
    </div>
  )
}
