import { LogOut } from 'lucide-react'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu'

import { initials } from '@/lib/initials'

import { useCurrentUser, useLogout } from './api'

export function UserMenu() {
  const navigate = useNavigate()
  const user = useCurrentUser()
  const logout = useLogout()

  if (!user.data) {
    return null
  }

  const signOut = async () => {
    try {
      await logout.mutateAsync()
    } catch {
      toast.error('Sign-out could not be confirmed by the server. Local session data was cleared.')
    }
    void navigate('/login', { replace: true })
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="icon" className="rounded-full" aria-label={`Account menu for ${user.data.displayName}`}>
          <span className="flex size-8 items-center justify-center rounded-full bg-muted text-xs font-semibold">
            {initials(user.data.displayName)}
          </span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-56">
        <DropdownMenuLabel className="space-y-0.5">
          <p className="truncate text-sm font-medium">{user.data.displayName}</p>
          <p className="truncate text-xs font-normal text-muted-foreground">{user.data.email}</p>
        </DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem onSelect={() => void signOut()} disabled={logout.isPending}>
          <LogOut aria-hidden="true" />
          Sign out
        </DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  )
}
