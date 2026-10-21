import { Navigate, type RouteObject } from 'react-router'

import { PublicOnly, RequireAuth } from '@/features/auth/guards'
import { LoginPage } from '@/features/auth/LoginPage'
import { RegisterPage } from '@/features/auth/RegisterPage'
import { OverviewPage } from '@/features/overview/OverviewPage'
import { ProjectLayout } from '@/features/projects/ProjectLayout'
import { ProjectsPage } from '@/features/projects/ProjectsPage'
import { OperationDetailView } from '@/features/specs/OperationDetailView'
import { SpecificationPage } from '@/features/specs/SpecificationPage'
import { SpecOverview } from '@/features/specs/SpecOverview'
import { TestsPage } from '@/features/tests/TestsPage'

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
          {
            path: 'projects/:projectId',
            element: <ProjectLayout />,
            children: [
              { index: true, element: <Navigate to="spec" replace /> },
              {
                path: 'spec',
                element: <SpecificationPage />,
                children: [
                  { index: true, element: <SpecOverview /> },
                  { path: 'operations/:operationId', element: <OperationDetailView /> },
                ],
              },
              { path: 'tests', element: <TestsPage /> },
            ],
          },
          { path: '*', element: <NotFoundPage /> },
        ],
      },
    ],
  },
]
