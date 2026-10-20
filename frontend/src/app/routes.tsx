import type { RouteObject } from 'react-router'

import { PublicOnly, RequireAuth } from '@/features/auth/guards'
import { LoginPage } from '@/features/auth/LoginPage'
import { RegisterPage } from '@/features/auth/RegisterPage'
import { OverviewPage } from '@/features/overview/OverviewPage'
import { ProjectsPage } from '@/features/projects/ProjectsPage'

import { AppShell } from './layout/AppShell'
import { NotFoundPage, RouteErrorPage } from './RouteErrorPage'

export const routes: RouteObject[] = [
  {
    path: '/login',
    element: (
      <PublicOnly>
        <LoginPage />
      </PublicOnly>
    ),
  },
  {
    path: '/register',
    element: (
      <PublicOnly>
        <RegisterPage />
      </PublicOnly>
    ),
  },
  {
    path: '/',
    element: <RequireAuth />,
    errorElement: <RouteErrorPage />,
    children: [
      {
        element: <AppShell />,
        children: [
          { index: true, element: <OverviewPage /> },
          { path: 'projects', element: <ProjectsPage /> },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
