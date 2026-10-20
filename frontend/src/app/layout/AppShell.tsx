import { Menu, Workflow } from 'lucide-react'
import { useState } from 'react'
import { Link, Outlet } from 'react-router'

import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle, SheetTrigger } from '@/components/ui/sheet'
import { useBackendHealth, useSystemInfo } from '@/features/system/api'
import { HealthBadge } from '@/features/system/HealthBadge'

import { SidebarNav } from './SidebarNav'

function Brand() {
  return (
    <Link to="/" className="flex items-center gap-2 rounded-md px-1 font-semibold tracking-tight">
      <span className="flex size-8 items-center justify-center rounded-md bg-primary text-primary-foreground">
        <Workflow className="size-4" aria-hidden="true" />
      </span>
      APIForge AI
    </Link>
  )
}

function BackendStatus() {
  const health = useBackendHealth()
  const status = health.isPending ? 'CHECKING' : health.isError ? 'UNREACHABLE' : health.data.status
  return (
    <div className="flex items-center gap-2 text-sm" aria-live="polite">
      <span className="hidden text-muted-foreground sm:inline">Backend</span>
      <HealthBadge status={status} />
    </div>
  )
}

function SidebarFooter() {
  const info = useSystemInfo()
  return (
    <p className="px-3 text-xs text-muted-foreground">
      {info.data ? `Version ${info.data.version}` : 'Version unavailable'}
    </p>
  )
}

/** Responsive application frame: fixed sidebar on large screens, slide-over navigation on small ones. */
export function AppShell() {
  const [mobileNavOpen, setMobileNavOpen] = useState(false)

  return (
    <div className="min-h-svh bg-muted/40">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:top-2 focus:left-2 focus:z-50 focus:rounded-md focus:bg-background focus:px-3 focus:py-2"
      >
        Skip to content
      </a>

      <aside className="fixed inset-y-0 left-0 hidden w-64 flex-col border-r bg-sidebar lg:flex">
        <div className="flex h-16 items-center px-4">
          <Brand />
        </div>
        <div className="flex-1 overflow-y-auto px-3 py-4">
          <SidebarNav />
        </div>
        <div className="border-t py-4">
          <SidebarFooter />
        </div>
      </aside>

      <div className="lg:pl-64">
        <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b bg-background/95 px-4 backdrop-blur sm:px-6">
          <Sheet open={mobileNavOpen} onOpenChange={setMobileNavOpen}>
            <SheetTrigger asChild>
              <Button variant="ghost" size="icon" className="lg:hidden" aria-label="Open navigation">
                <Menu aria-hidden="true" />
              </Button>
            </SheetTrigger>
            <SheetContent side="left" className="w-72 bg-sidebar p-0">
              <SheetHeader className="h-16 justify-center px-4">
                <SheetTitle asChild>
                  <div>
                    <Brand />
                  </div>
                </SheetTitle>
                <SheetDescription className="sr-only">Main navigation</SheetDescription>
              </SheetHeader>
              <div className="px-3 py-4">
                <SidebarNav onNavigate={() => setMobileNavOpen(false)} />
              </div>
            </SheetContent>
          </Sheet>
          <div className="lg:hidden">
            <Brand />
          </div>
          <div className="ml-auto">
            <BackendStatus />
          </div>
        </header>

        <main id="main-content" className="mx-auto w-full max-w-7xl px-4 py-6 sm:px-6 lg:py-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
