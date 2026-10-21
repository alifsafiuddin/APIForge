import { ArrowLeft } from 'lucide-react'
import { Link, useParams } from 'react-router'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

import { type JsonObject, type OperationDetail, useComponentSchemas, useOperation } from './api'
import { ImportMessages } from './components/ImportMessages'
import { MethodBadge, StatusBadge } from './components/MethodBadge'
import { SchemaView } from './components/SchemaView'
import { typeLabel } from './components/schema-utils'
import { useSpecContext } from './SpecContext'

const PARAMETER_LOCATIONS = ['path', 'query', 'header', 'cookie'] as const

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function asObject(value: unknown): JsonObject | undefined {
  return typeof value === 'object' && value !== null && !Array.isArray(value) ? (value as JsonObject) : undefined
}

export function OperationDetailView() {
  const { operationId } = useParams()
  const { projectId, version, versionQuery } = useSpecContext()
  const operation = useOperation(projectId, version.id, operationId)
  const components = useComponentSchemas(projectId, version.id)

  if (operation.isPending || components.isPending) {
    return (
      <Card aria-label="Loading endpoint">
        <CardContent className="space-y-3">
          <Skeleton className="h-6 w-2/3" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-24 w-full" />
        </CardContent>
      </Card>
    )
  }
  if (operation.isError || components.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{(operation.error ?? components.error)?.message}</AlertDescription>
      </Alert>
    )
  }

  const op = operation.data
  const schemas = components.data
  return (
    <article className="space-y-4" aria-labelledby="operation-title">
      <Link
        to={{ pathname: '..', search: versionQuery }}
        relative="path"
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground lg:hidden"
      >
        <ArrowLeft className="size-4" aria-hidden="true" />
        All endpoints
      </Link>
      <Card>
        <CardHeader className="space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <MethodBadge method={op.method} />
            <h2 id="operation-title" className="font-mono text-base font-semibold break-all">
              {op.path}
            </h2>
            {op.deprecated ? <Badge variant="secondary">Deprecated</Badge> : null}
          </div>
          {op.summary ? <p className="font-medium">{op.summary}</p> : null}
          <dl className="flex flex-wrap gap-x-6 gap-y-1 text-xs text-muted-foreground">
            {op.operationId ? (
              <div>
                <dt className="inline">operationId: </dt>
                <dd className="inline font-mono">{op.operationId}</dd>
              </div>
            ) : null}
            {op.tags.length > 0 ? (
              <div>
                <dt className="inline">Tags: </dt>
                <dd className="inline">{op.tags.join(', ')}</dd>
              </div>
            ) : null}
          </dl>
        </CardHeader>
        <CardContent className="space-y-3 text-sm">
          {op.description ? <p className="whitespace-pre-wrap text-muted-foreground">{op.description}</p> : null}
          <SecuritySummary security={op.security} />
          <ImportMessages messages={op.warnings} label="Import warnings for this endpoint" />
        </CardContent>
      </Card>

      <Parameters operation={op} components={schemas} />
      <RequestBody body={op.requestBody} components={schemas} />
      <Responses responses={op.responses} components={schemas} />
    </article>
  )
}

function SecuritySummary({ security }: { security: OperationDetail['security'] }) {
  if (security.length === 0) {
    return <p className="text-xs text-muted-foreground">Authentication: none required.</p>
  }
  return (
    <div className="text-xs">
      <span className="text-muted-foreground">Authentication (any one of): </span>
      {security.map((requirement, index) => (
        <span key={index}>
          {index > 0 ? ' or ' : ''}
          {Object.entries(requirement)
            .map(([scheme, scopes]) => (scopes.length > 0 ? `${scheme} [${scopes.join(', ')}]` : scheme))
            .join(' + ') || 'none'}
        </span>
      ))}
    </div>
  )
}

function Parameters({ operation, components }: { operation: OperationDetail; components: JsonObject }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Parameters</CardTitle>
      </CardHeader>
      <CardContent>
        {operation.parameters.length === 0 ? (
          <p className="text-sm text-muted-foreground">No parameters.</p>
        ) : (
          <div className="space-y-4">
            {PARAMETER_LOCATIONS.map((location) => {
              const parameters = operation.parameters.filter((parameter) => parameter.in === location)
              if (parameters.length === 0) {
                return null
              }
              return (
                <section key={location} aria-label={`${location} parameters`}>
                  <h3 className="mb-2 text-xs font-medium tracking-wide text-muted-foreground uppercase">{location}</h3>
                  <ul className="divide-y rounded-lg border">
                    {parameters.map((parameter, index) => (
                      <li key={`${text(parameter.name)}-${index}`} className="space-y-1.5 p-3">
                        <div className="flex flex-wrap items-baseline gap-2">
                          <span className="font-mono text-sm font-semibold">{text(parameter.name)}</span>
                          <span className="font-mono text-xs text-muted-foreground">{typeLabel(parameter.schema)}</span>
                          {parameter.required === true ? (
                            <span className="rounded bg-red-50 px-1.5 py-0.5 text-[0.7rem] font-medium text-red-700">
                              required
                            </span>
                          ) : null}
                          {parameter.deprecated === true ? <Badge variant="secondary">deprecated</Badge> : null}
                        </div>
                        {text(parameter.description) ? (
                          <p className="text-xs whitespace-pre-wrap text-muted-foreground">{text(parameter.description)}</p>
                        ) : null}
                        {parameter.schema !== undefined ? (
                          <SchemaView schema={parameter.schema} components={components} />
                        ) : null}
                      </li>
                    ))}
                  </ul>
                </section>
              )
            })}
          </div>
        )}
      </CardContent>
    </Card>
  )
}

function MediaTypes({ content, components }: { content: unknown; components: JsonObject }) {
  const media = asObject(content)
  if (!media || Object.keys(media).length === 0) {
    return <p className="text-xs text-muted-foreground">No body.</p>
  }
  return (
    <div className="space-y-3">
      {Object.entries(media).map(([mediaType, definition]) => (
        <div key={mediaType} className="rounded-lg border p-3">
          <p className="mb-2 font-mono text-xs text-muted-foreground">{mediaType}</p>
          {asObject(definition)?.schema !== undefined ? (
            <SchemaView schema={asObject(definition)?.schema} components={components} />
          ) : (
            <p className="text-xs text-muted-foreground">No schema documented.</p>
          )}
        </div>
      ))}
    </div>
  )
}

function RequestBody({ body, components }: { body: JsonObject | null; components: JsonObject }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>
          Request body{' '}
          {body?.required === true ? <span className="text-xs font-normal text-red-700">(required)</span> : null}
        </CardTitle>
      </CardHeader>
      <CardContent className="space-y-2">
        {body === null ? (
          <p className="text-sm text-muted-foreground">This endpoint takes no request body.</p>
        ) : (
          <>
            {text(body.description) ? <p className="text-sm text-muted-foreground">{text(body.description)}</p> : null}
            <MediaTypes content={body.content} components={components} />
          </>
        )}
      </CardContent>
    </Card>
  )
}

function Responses({ responses, components }: { responses: JsonObject[]; components: JsonObject }) {
  return (
    <Card>
      <CardHeader>
        <CardTitle>Responses</CardTitle>
      </CardHeader>
      <CardContent>
        {responses.length === 0 ? (
          <p className="text-sm text-muted-foreground">No responses documented.</p>
        ) : (
          <ul className="space-y-4" aria-label="Documented responses">
            {responses.map((response) => {
              const headers = asObject(response.headers)
              return (
                <li key={String(response.status)} className="space-y-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <StatusBadge status={String(response.status)} />
                    <span className="text-sm">{text(response.description) ?? ''}</span>
                  </div>
                  {headers && Object.keys(headers).length > 0 ? (
                    <div className="text-xs">
                      <span className="text-muted-foreground">Headers: </span>
                      {Object.entries(headers).map(([name, header], index) => (
                        <span key={name}>
                          {index > 0 ? ', ' : ''}
                          <code>{name}</code> ({typeLabel(asObject(header)?.schema)})
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {response.content !== undefined ? <MediaTypes content={response.content} components={components} /> : null}
                </li>
              )
            })}
          </ul>
        )}
      </CardContent>
    </Card>
  )
}
