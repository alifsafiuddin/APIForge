import { FolderKanban, LayoutDashboard, type LucideIcon, Settings } from 'lucide-react'

export type NavItem = {
  to: string
  label: string
  icon: LucideIcon
  /** Match only the exact path (for the index route). */
  end?: boolean
}

export type NavSection = {
  title: string
  items: NavItem[]
}

/**
 * Sidebar navigation. Only pages that are actually implemented are listed here; new
 * entries are added in the phase that delivers each page (see docs/PROJECT_STATUS.md).
 */
export const NAV_SECTIONS: NavSection[] = [
  {
    title: 'Workspace',
    items: [
      { to: '/', label: 'Overview', icon: LayoutDashboard, end: true },
      { to: '/projects', label: 'Projects', icon: FolderKanban },
    ],
  },
  {
    title: 'Account',
    items: [{ to: '/settings', label: 'Settings', icon: Settings }],
  },
]
