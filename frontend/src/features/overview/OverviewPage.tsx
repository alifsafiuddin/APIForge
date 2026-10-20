import { PageHeader } from '@/components/PageHeader'
import { SystemStatusCard } from '@/features/system/SystemStatusCard'

export function OverviewPage() {
  return (
    <div className="space-y-6">
      <PageHeader
        title="Overview"
        description="Contract-driven API testing from your OpenAPI specifications."
      />
      <div className="grid gap-6 xl:grid-cols-3">
        <div className="xl:col-span-2">
          <SystemStatusCard />
        </div>
      </div>
    </div>
  )
}
