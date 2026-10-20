import { RefreshCw } from 'lucide-react'

import { ApiError } from '@/api/client'
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardAction, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'

import { useBackendHealth, useSystemInfo } from './api'
import { HealthBadge, type DisplayStatus } from './HealthBadge'

const COMPONENT_LABELS: Record<string, string> = {
  db: 'PostgreSQL',
  diskSpace: 'Disk space',
  livenessState: 'Liveness',
  readinessState: 'Readiness',
  ping: 'Ping',
  ssl: 'TLS certificates',
}

/** Live backend status from Actuator health and the system info endpoint. Nothing here is simulated. */
export function SystemStatusCard() {
  const health = useBackendHealth()
  const info = useSystemInfo()

  const overall: DisplayStatus = health.isPending ? 'CHECKING' : health.isError ? 'UNREACHABLE' : health.data.status
  const components = health.data?.components ? Object.entries(health.data.components) : []

  return (
    <Card>
      <CardHeader>
        <CardTitle>System status</CardTitle>
        <CardDescription>Live health reported by the APIForge backend.</CardDescription>
        <CardAction>
          <Button
            variant="outline"
            size="sm"
            onClick={() => {
              void health.refetch()
              void info.refetch()
            }}
            disabled={health.isFetching}
          >
            <RefreshCw className={health.isFetching ? 'animate-spin' : undefined} aria-hidden="true" />
            Refresh
          </Button>
        </CardAction>
      </CardHeader>
      <CardContent className="space-y-6">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <span className="text-sm font-medium">Backend</span>
          <HealthBadge status={overall} />
        </div>

        {health.isError ? (
          <Alert variant="destructive">
            <AlertTitle>Backend unavailable</AlertTitle>
            <AlertDescription>
              <p>{health.error.message}</p>
              <p>Start it with <code className="font-mono">./mvnw spring-boot:run</code> in the backend folder.</p>
              {health.error instanceof ApiError && health.error.requestId ? (
                <p className="font-mono text-xs">Request ID: {health.error.requestId}</p>
              ) : null}
            </AlertDescription>
          </Alert>
        ) : null}

        {health.isPending ? (
          <div className="space-y-3" aria-label="Loading component status">
            <Skeleton className="h-5 w-full" />
            <Skeleton className="h-5 w-full" />
          </div>
        ) : components.length > 0 ? (
          <ul className="divide-y rounded-lg border" aria-label="Health components">
            {components.map(([name, component]) => (
              <li key={name} className="flex items-center justify-between gap-3 px-4 py-2.5 text-sm">
                <span>{COMPONENT_LABELS[name] ?? name}</span>
                <HealthBadge status={component.status} />
              </li>
            ))}
          </ul>
        ) : null}

        <dl className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-2">
          <InfoItem label="Version" value={info.data?.version} loading={info.isPending} />
          <InfoItem label="API version" value={info.data?.apiVersion} loading={info.isPending} />
        </dl>
      </CardContent>
    </Card>
  )
}

function InfoItem({ label, value, loading }: { label: string; value: string | undefined; loading: boolean }) {
  return (
    <div className="space-y-1">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="font-medium">{loading ? <Skeleton className="h-5 w-24" /> : (value ?? '—')}</dd>
    </div>
  )
}
