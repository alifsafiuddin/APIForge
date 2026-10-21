import { KeyRound, Plus, Server } from 'lucide-react'
import { useState } from 'react'
import { useParams } from 'react-router'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'

import {
  AUTH_LABELS,
  type EnvironmentDetail,
  POLICY_LABELS,
  useDeleteEnvironment,
  useDeleteVariable,
  useEnvironments,
  usePutVariable,
} from './api'
import { EnvironmentFormDialog } from './EnvironmentFormDialog'

/** Execution environments: target, policy, authentication, default headers and variables. */
export function EnvironmentsPage() {
  const { projectId = '' } = useParams()
  const environments = useEnvironments(projectId)
  const [editing, setEditing] = useState<EnvironmentDetail | null>(null)
  const [formOpen, setFormOpen] = useState(false)

  const openForm = (environment: EnvironmentDetail | null) => {
    setEditing(environment)
    setFormOpen(true)
  }

  if (environments.isPending) {
    return <Skeleton className="h-64" aria-label="Loading environments" />
  }
  if (environments.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{environments.error.message}</AlertDescription>
      </Alert>
    )
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <p className="text-sm text-muted-foreground">
          Requests only go to the environment&apos;s base URL. Private, loopback, link-local and cloud metadata
          destinations are blocked, also after redirects.
        </p>
        <Button onClick={() => openForm(null)}>
          <Plus aria-hidden="true" />
          New environment
        </Button>
      </div>
      {environments.data.length === 0 ? (
        <Card className="items-center gap-3 px-6 py-12 text-center">
          <Server className="size-8 text-muted-foreground" aria-hidden="true" />
          <h2 className="font-medium">No environments yet</h2>
          <p className="max-w-md text-sm text-muted-foreground">
            Add the base URL of an API you are authorized to test, with its authentication, to run tests.
          </p>
        </Card>
      ) : (
        environments.data.map((environment) => (
          <EnvironmentCard key={environment.id} projectId={projectId} environment={environment} onEdit={() => openForm(environment)} />
        ))
      )}
      {formOpen ? (
        <EnvironmentFormDialog
          key={editing?.id ?? 'new'}
          projectId={projectId}
          environment={editing}
          open={formOpen}
          onOpenChange={setFormOpen}
          firstEnvironment={environments.data.length === 0}
        />
      ) : null}
    </div>
  )
}

function EnvironmentCard({
  projectId,
  environment,
  onEdit,
}: {
  projectId: string
  environment: EnvironmentDetail
  onEdit: () => void
}) {
  const remove = useDeleteEnvironment(projectId)
  const destroy = async () => {
    if (!window.confirm(`Delete environment "${environment.name}"? Runs keep their history.`)) return
    try {
      await remove.mutateAsync(environment.id)
      toast.success('Environment deleted.')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The environment could not be deleted.')
    }
  }
  return (
    <Card className="gap-3 p-4" aria-label={`Environment ${environment.name}`}>
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="font-medium">{environment.name}</h2>
          <p className="truncate font-mono text-xs text-muted-foreground">{environment.baseUrl}</p>
        </div>
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={onEdit}>
            Edit
          </Button>
          <Button size="sm" variant="ghost" onClick={() => void destroy()} disabled={remove.isPending}>
            Delete
          </Button>
        </div>
      </div>
      <div className="flex flex-wrap gap-1.5 text-xs">
        <Badge variant={environment.executionPolicy === 'READ_ONLY' ? 'secondary' : 'destructive'}>
          {POLICY_LABELS[environment.executionPolicy]}
        </Badge>
        <Badge variant="outline">Auth: {AUTH_LABELS[environment.auth.type]}</Badge>
        <Badge variant="outline">Timeout {environment.timeoutMs} ms</Badge>
        <Badge variant="outline">{environment.followRedirects ? 'Follows redirects' : 'No redirects'}</Badge>
        {Object.keys(environment.defaultHeaders).length > 0 ? (
          <Badge variant="outline">{Object.keys(environment.defaultHeaders).length} default headers</Badge>
        ) : null}
      </div>
      <Variables projectId={projectId} environment={environment} />
    </Card>
  )
}

function Variables({ projectId, environment }: { projectId: string; environment: EnvironmentDetail }) {
  const put = usePutVariable(projectId, environment.id)
  const remove = useDeleteVariable(projectId, environment.id)
  const [name, setName] = useState('')
  const [value, setValue] = useState('')
  const [secret, setSecret] = useState(true)

  const add = async (event: React.FormEvent) => {
    event.preventDefault()
    try {
      await put.mutateAsync({ name: name.trim(), value, secret })
      toast.success(secret ? `Secret ${name.trim()} saved (it cannot be shown again).` : `Variable ${name.trim()} saved.`)
      setName('')
      setValue('')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The variable could not be saved.')
    }
  }

  return (
    <section aria-label={`Variables of ${environment.name}`} className="space-y-2 border-t pt-3">
      <h3 className="text-sm font-medium">Variables</h3>
      {environment.variables.length === 0 ? (
        <p className="text-xs text-muted-foreground">No variables. Reference them as {'{{NAME}}'} in headers, queries and bodies.</p>
      ) : (
        <ul className="divide-y rounded-lg border text-sm">
          {environment.variables.map((variable) => (
            <li key={variable.name} className="flex items-center gap-2 px-3 py-1.5">
              {variable.secret ? <KeyRound className="size-3.5 text-muted-foreground" aria-label="Secret" /> : null}
              <span className="font-mono text-xs">{variable.name}</span>
              <span className="min-w-0 flex-1 truncate font-mono text-xs text-muted-foreground">
                {variable.secret ? (variable.hasValue ? '•••••• (write-only)' : 'not set') : variable.value}
              </span>
              <Button size="xs" variant="ghost" aria-label={`Delete variable ${variable.name}`} onClick={() => void remove.mutateAsync(variable.name)}>
                Delete
              </Button>
            </li>
          ))}
        </ul>
      )}
      <form onSubmit={(event) => void add(event)} className="flex flex-wrap items-center gap-2">
        <Input aria-label="Variable name" placeholder="NAME" className="h-8 w-36 font-mono" value={name} onChange={(event) => setName(event.target.value)} />
        <Input
          aria-label="Variable value"
          placeholder="value"
          type={secret ? 'password' : 'text'}
          autoComplete="off"
          className="h-8 min-w-0 flex-1 font-mono"
          value={value}
          onChange={(event) => setValue(event.target.value)}
        />
        <label className="flex items-center gap-1.5 text-xs">
          <input type="checkbox" className="size-4" checked={secret} onChange={(event) => setSecret(event.target.checked)} />
          Secret
        </label>
        <Button size="sm" type="submit" disabled={put.isPending || name.trim() === ''}>
          Save variable
        </Button>
      </form>
    </section>
  )
}
