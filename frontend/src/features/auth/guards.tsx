import type { ReactNode } from 'react'
import { Navigate, Outlet, useLocation, useSearchParams } from 'react-router'

import { FullPageError, FullPageLoader } from '@/components/FullPageState'

import { useCurrentUser } from './api'
import { loginPathFor, safeRedirectPath } from './redirect'

/** Renders child routes only for signed-in users; everyone else is sent to the login page. */
export function RequireAuth() {
  const location = useLocation()
  const user = useCurrentUser()

  if (user.isPending) {
    return <FullPageLoader label="Checking your session…" />
  }
  if (user.isError) {
    return (
      <FullPageError
        title="Cannot reach APIForge"
        message={user.error.message}
        onRetry={() => void user.refetch()}
      />
    )
  }
  if (user.data === null) {
    return <Navigate to={loginPathFor(location)} replace />
  }
  return <Outlet />
}

/** Login and registration pages: signed-in users go straight to where they were heading. */
export function PublicOnly({ children }: { children: ReactNode }) {
  const [searchParams] = useSearchParams()
  const user = useCurrentUser()

  if (user.isPending) {
    return <FullPageLoader label="Checking your session…" />
  }
  if (user.data) {
    return <Navigate to={safeRedirectPath(searchParams.get('next'))} replace />
  }
  return children
}
