import type { RouteObject } from 'react-router'

import { OverviewPage } from '@/features/overview/OverviewPage'

import { AppShell } from './layout/AppShell'
import { NotFoundPage, RouteErrorPage } from './RouteErrorPage'

export const routes: RouteObject[] = [
  {
    path: '/',
    element: <AppShell />,
    errorElement: <RouteErrorPage />,
    children: [
      { index: true, element: <OverviewPage /> },
      { path: '*', element: <NotFoundPage /> },
    ],
  },
]
