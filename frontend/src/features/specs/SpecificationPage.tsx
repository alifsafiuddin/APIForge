import { FileCode2, Upload } from 'lucide-react'
import { useState } from 'react'
import { Outlet, useNavigate, useParams, useSearchParams } from 'react-router'
import { toast } from 'sonner'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { cn } from '@/lib/utils'

import { useActivateVersion, useOperations, useSpecVersions } from './api'
import { ImportSpecificationDialog } from './ImportSpecificationDialog'
import { OperationList } from './OperationList'
import type { SpecOutletContext } from './SpecContext'

/**
 * The specification explorer of one project: version selection, the endpoint index and either
 * the version overview or one endpoint (nested routes).
 */
export function SpecificationPage() {
  const { projectId = '', operationId } = useParams()
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [importing, setImporting] = useState(false)
  const versions = useSpecVersions(projectId)
  const activateVersion = useActivateVersion(projectId)

  const all = versions.data?.content ?? []
  const requested = searchParams.get('version')
  const version = all.find((candidate) => candidate.id === requested) ?? all.find((candidate) => candidate.active) ?? all[0]
  const operations = useOperations(projectId, version?.id)
  const versionQuery = version && !version.active ? `?version=${version.id}` : ''

  const selectVersion = (versionId: string) => {
    const target = all.find((candidate) => candidate.id === versionId)
    void navigate({ pathname: `/projects/${projectId}/spec`, search: target?.active ? '' : `?version=${versionId}` })
  }

  const activate = async () => {
    if (!version) return
    try {
      await activateVersion.mutateAsync(version.id)
      toast.success(`Version ${version.versionNumber} is now active.`)
      void navigate({ pathname: `/projects/${projectId}/spec` })
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The version could not be activated.')
    }
  }

  if (versions.isPending) {
    return (
      <div className="grid gap-4 lg:grid-cols-[22rem_1fr]" aria-label="Loading specification">
        <Skeleton className="h-96" />
        <Skeleton className="h-96" />
      </div>
    )
  }
  if (versions.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{versions.error.message}</AlertDescription>
      </Alert>
    )
  }

  const dialog = (
    <ImportSpecificationDialog
      projectId={projectId}
      open={importing}
      onOpenChange={setImporting}
      hasVersions={all.length > 0}
      onImported={(result) => {
        setImporting(false)
        void navigate({
          pathname: `/projects/${projectId}/spec`,
          search: result.version.active ? '' : `?version=${result.version.id}`,
        })
      }}
    />
  )

  if (!version) {
    return (
      <>
        <Card className="items-center gap-3 px-6 py-12 text-center">
          <FileCode2 className="size-8 text-muted-foreground" aria-hidden="true" />
          <h2 className="font-medium">No specification yet</h2>
          <p className="max-w-md text-sm text-muted-foreground">
            Import an OpenAPI 3.0 or 3.1 document (JSON or YAML) to explore its endpoints, parameters, schemas and
            responses.
          </p>
          <Button onClick={() => setImporting(true)} className="mt-2">
            <Upload aria-hidden="true" />
            Import specification
          </Button>
        </Card>
        {dialog}
      </>
    )
  }

  const context: SpecOutletContext = { projectId, version, versionQuery }

  return (
    <div className="space-y-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex min-w-0 flex-wrap items-center gap-2">
          <label htmlFor="spec-version" className="text-sm text-muted-foreground">
            Version
          </label>
          <select
            id="spec-version"
            className="h-8 w-full max-w-full min-w-0 truncate rounded-lg border border-input bg-background px-2.5 text-sm sm:w-auto sm:max-w-md"
            value={version.id}
            onChange={(event) => selectVersion(event.target.value)}
          >
            {all.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                v{candidate.versionNumber} · {candidate.title}
                {candidate.apiVersion ? ` ${candidate.apiVersion}` : ''}
                {candidate.active ? ' (active)' : ''}
              </option>
            ))}
          </select>
          {!version.active ? (
            <Button variant="outline" size="sm" onClick={() => void activate()} disabled={activateVersion.isPending}>
              Make active
            </Button>
          ) : null}
        </div>
        <Button onClick={() => setImporting(true)}>
          <Upload aria-hidden="true" />
          Import new version
        </Button>
      </div>

      <div className="grid gap-4 lg:grid-cols-[22rem_minmax(0,1fr)]">
        <Card className={cn('h-fit p-3 lg:sticky lg:top-20', operationId && 'hidden lg:block')}>
          {operations.isPending ? (
            <div className="space-y-2" aria-label="Loading endpoints">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-6 w-full" />
              <Skeleton className="h-6 w-full" />
            </div>
          ) : operations.isError ? (
            <p className="text-sm text-destructive">{operations.error.message}</p>
          ) : (
            <OperationList operations={operations.data} versionQuery={versionQuery} />
          )}
        </Card>
        <div className="min-w-0">
          <Outlet context={context} />
        </div>
      </div>
      {dialog}
    </div>
  )
}
