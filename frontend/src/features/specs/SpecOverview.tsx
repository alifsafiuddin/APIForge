import { Download } from 'lucide-react'

import { Alert, AlertDescription } from '@/components/ui/alert'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { formatDateTime } from '@/lib/format'

import { type JsonObject, rawDownloadUrl, useSpecVersion } from './api'
import { ImportMessages } from './components/ImportMessages'
import { countBySeverity } from './messages'
import { useSpecContext } from './SpecContext'

function text(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined
}

function formatBytes(bytes: number): string {
  return bytes >= 1024 * 1024 ? `${(bytes / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`
}

/** Version metadata, servers, security schemes and import findings. */
export function SpecOverview() {
  const { projectId, version } = useSpecContext()
  const detail = useSpecVersion(projectId, version.id)

  if (detail.isPending) {
    return (
      <Card aria-label="Loading specification details">
        <CardContent className="space-y-3">
          <Skeleton className="h-6 w-1/2" />
          <Skeleton className="h-4 w-full" />
          <Skeleton className="h-4 w-3/4" />
        </CardContent>
      </Card>
    )
  }
  if (detail.isError) {
    return (
      <Alert variant="destructive">
        <AlertDescription>{detail.error.message}</AlertDescription>
      </Alert>
    )
  }

  const { summary, description, servers, securitySchemes, tags, messages } = detail.data
  const counts = countBySeverity(messages)

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader>
          <CardTitle>
            <h2 className="text-lg">{summary.title}</h2>
          </CardTitle>
          <CardDescription className="flex flex-wrap items-center gap-2">
            {summary.apiVersion ? <span>API version {summary.apiVersion}</span> : null}
            <Badge variant="outline">OpenAPI {summary.openapiVersion}</Badge>
            <Badge variant="outline">{summary.sourceFormat}</Badge>
            {summary.active ? <Badge>Active</Badge> : <Badge variant="secondary">Not active</Badge>}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4 text-sm">
          {description ? <p className="whitespace-pre-wrap text-muted-foreground">{description}</p> : null}
          <dl className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <div>
              <dt className="text-muted-foreground">Endpoints</dt>
              <dd className="font-medium">{summary.operationCount}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Imported</dt>
              <dd className="font-medium">{formatDateTime(summary.importedAt)}</dd>
            </div>
            <div>
              <dt className="text-muted-foreground">Source</dt>
              <dd className="truncate font-medium" title={summary.sourceName ?? undefined}>
                {summary.sourceName ?? '—'} · {formatBytes(summary.contentSizeBytes)}
              </dd>
            </div>
            <div>
              <dt className="text-muted-foreground">SHA-256</dt>
              <dd className="font-mono text-xs" title={summary.contentSha256}>
                {summary.contentSha256.slice(0, 12)}…
              </dd>
            </div>
          </dl>
          <Button variant="outline" size="sm" asChild>
            <a href={rawDownloadUrl(projectId, summary.id)} download>
              <Download aria-hidden="true" />
              Download original
            </a>
          </Button>
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Servers</CardTitle>
          <CardDescription>As documented. Tests will use the base URL of an environment you configure.</CardDescription>
        </CardHeader>
        <CardContent>
          {servers.length === 0 ? (
            <p className="text-sm text-muted-foreground">No servers declared.</p>
          ) : (
            <ul className="space-y-2 text-sm">
              {servers.map((server, index) => (
                <li key={index}>
                  <code className="font-mono text-xs break-all">{text(server.url) ?? '(no url)'}</code>
                  {text(server.description) ? (
                    <span className="block text-xs text-muted-foreground">{text(server.description)}</span>
                  ) : null}
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      <Card>
        <CardHeader>
          <CardTitle>Security schemes</CardTitle>
        </CardHeader>
        <CardContent>
          {Object.keys(securitySchemes).length === 0 ? (
            <p className="text-sm text-muted-foreground">No security schemes declared.</p>
          ) : (
            <ul className="divide-y text-sm">
              {Object.entries(securitySchemes).map(([name, scheme]) => (
                <li key={name} className="flex flex-wrap items-baseline gap-2 py-2">
                  <span className="font-mono text-xs font-semibold">{name}</span>
                  <SchemeDetails scheme={scheme as JsonObject} />
                </li>
              ))}
            </ul>
          )}
        </CardContent>
      </Card>

      {tags.length > 0 ? (
        <Card>
          <CardHeader>
            <CardTitle>Tags</CardTitle>
          </CardHeader>
          <CardContent>
            <ul className="space-y-1.5 text-sm">
              {tags.map((tag, index) => (
                <li key={index}>
                  <span className="font-medium">{text(tag.name)}</span>
                  {text(tag.description) ? <span className="text-muted-foreground"> — {text(tag.description)}</span> : null}
                </li>
              ))}
            </ul>
          </CardContent>
        </Card>
      ) : null}

      <Card>
        <CardHeader>
          <CardTitle>Import findings</CardTitle>
          <CardDescription>
            {messages.length === 0
              ? 'No warnings. Everything in this document is supported.'
              : `${counts.warnings} warning(s), ${counts.notes} note(s). The document was imported; these explain what is documented but not (yet) testable.`}
          </CardDescription>
        </CardHeader>
        {messages.length > 0 ? (
          <CardContent>
            <ImportMessages messages={messages} label="Import findings" />
          </CardContent>
        ) : null}
      </Card>
    </div>
  )
}

function SchemeDetails({ scheme }: { scheme: JsonObject }) {
  const type = text(scheme.type) ?? 'unknown'
  const parts: string[] = [type]
  if (text(scheme.scheme)) parts.push(`scheme: ${text(scheme.scheme)}`)
  if (text(scheme.bearerFormat)) parts.push(`format: ${text(scheme.bearerFormat)}`)
  if (text(scheme.in)) parts.push(`in ${text(scheme.in)}: ${text(scheme.name) ?? ''}`)
  if (typeof scheme.flows === 'object' && scheme.flows !== null) parts.push(`flows: ${Object.keys(scheme.flows).join(', ')}`)
  return <span className="text-xs text-muted-foreground">{parts.join(' · ')}</span>
}
