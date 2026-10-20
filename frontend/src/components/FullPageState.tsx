import { Loader2 } from 'lucide-react'

import { Button } from '@/components/ui/button'

export function FullPageLoader({ label }: { label: string }) {
  return (
    <output className="flex min-h-svh items-center justify-center gap-3 text-sm text-muted-foreground">
      <Loader2 className="size-5 animate-spin" aria-hidden="true" />
      {label}
    </output>
  )
}

export function FullPageError({ title, message, onRetry }: { title: string; message: string; onRetry: () => void }) {
  return (
    <div className="flex min-h-svh flex-col items-center justify-center gap-4 px-4 text-center" role="alert">
      <h1 className="text-xl font-semibold tracking-tight">{title}</h1>
      <p className="max-w-md text-sm text-muted-foreground">{message}</p>
      <Button onClick={onRetry}>Try again</Button>
    </div>
  )
}
