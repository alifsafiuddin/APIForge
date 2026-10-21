import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Label } from '@/components/ui/label'
import { POLICY_LABELS, useEnvironments } from '@/features/environments/api'

import { useStartRun } from './api'

type Scope = 'operation' | 'selected' | 'all'

/** Starts a run of the endpoint's tests, the selected tests or all runnable tests. */
export function RunTestsDialog({
  projectId,
  open,
  onOpenChange,
  operationKey,
  selectedTestIds,
}: {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
  operationKey: string | undefined
  selectedTestIds: string[]
}) {
  const environments = useEnvironments(projectId)
  const start = useStartRun(projectId)
  const navigate = useNavigate()
  const [environmentId, setEnvironmentId] = useState('')
  const [scope, setScope] = useState<Scope>(selectedTestIds.length > 0 ? 'selected' : operationKey ? 'operation' : 'all')
  const chosen = environmentId || environments.data?.[0]?.id || ''
  const environment = environments.data?.find((candidate) => candidate.id === chosen)

  const run = async () => {
    try {
      const body =
        scope === 'selected'
          ? { environmentId: chosen, testIds: selectedTestIds }
          : scope === 'operation' && operationKey
            ? { environmentId: chosen, operationKey }
            : { environmentId: chosen }
      const started = await start.mutateAsync(body)
      onOpenChange(false)
      void navigate(`/projects/${projectId}/runs/${started.id}`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The run could not be started.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Run tests</DialogTitle>
          <DialogDescription>Enabled, approved tests are sent to the environment&apos;s base URL.</DialogDescription>
        </DialogHeader>
        {environments.data && environments.data.length === 0 ? (
          <p className="text-sm">
            Create an environment first on the{' '}
            <Link className="underline" to={`/projects/${projectId}/environments`}>
              Environments
            </Link>{' '}
            tab.
          </p>
        ) : (
          <div className="space-y-3 text-sm">
            <div className="space-y-1.5">
              <Label htmlFor="run-environment">Environment</Label>
              <select
                id="run-environment"
                className="h-9 w-full rounded-lg border border-input bg-background px-2.5"
                value={chosen}
                onChange={(event) => setEnvironmentId(event.target.value)}
              >
                {environments.data?.map((candidate) => (
                  <option key={candidate.id} value={candidate.id}>
                    {candidate.name} — {candidate.baseUrl}
                  </option>
                ))}
              </select>
              {environment ? (
                <p className="text-xs text-muted-foreground">
                  Policy: {POLICY_LABELS[environment.executionPolicy]}. Tests above it are skipped, not sent.
                </p>
              ) : null}
            </div>
            <fieldset className="space-y-1">
              <legend className="mb-1 font-medium">Tests</legend>
              {selectedTestIds.length > 0 ? (
                <Radio value="selected" scope={scope} onChange={setScope} label={`The ${selectedTestIds.length} selected tests`} />
              ) : null}
              {operationKey ? <Radio value="operation" scope={scope} onChange={setScope} label={`All enabled tests of ${operationKey}`} /> : null}
              <Radio value="all" scope={scope} onChange={setScope} label="All enabled tests of the project" />
            </fieldset>
          </div>
        )}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          <Button onClick={() => void run()} disabled={!chosen || start.isPending}>
            {start.isPending ? 'Starting…' : 'Start run'}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Radio({ value, scope, onChange, label }: { value: Scope; scope: Scope; onChange: (scope: Scope) => void; label: string }) {
  return (
    <label className="flex items-center gap-2">
      <input type="radio" name="run-scope" className="size-4" checked={scope === value} onChange={() => onChange(value)} />
      {label}
    </label>
  )
}
