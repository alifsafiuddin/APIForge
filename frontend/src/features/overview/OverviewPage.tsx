import { PageHeader } from '@/components/PageHeader'
import { useCurrentUser } from '@/features/auth/api'
import { RecentProjectsCard } from '@/features/projects/RecentProjectsCard'
import { SystemStatusCard } from '@/features/system/SystemStatusCard'

export function OverviewPage() {
  const user = useCurrentUser()

  return (
    <div className="space-y-6">
      <PageHeader
        title="Overview"
        description={
          user.data ? `Welcome back, ${user.data.displayName}.` : 'Contract-driven API testing from your OpenAPI specifications.'
        }
      />
      <div className="grid gap-6 xl:grid-cols-2">
        <RecentProjectsCard />
        <SystemStatusCard />
      </div>
    </div>
  )
}
