import { useState } from 'react'
import { toast } from 'sonner'

import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

import { type Suite, useCreateSuite, useDeleteSuite, useRenameSuite, useSuites } from './api'

/** Create, rename and delete suites. Only empty custom suites can be deleted, so no test is lost. */
export function SuitesDialog({
  projectId,
  open,
  onOpenChange,
}: {
  projectId: string
  open: boolean
  onOpenChange: (open: boolean) => void
}) {
  const suites = useSuites(projectId)
  const create = useCreateSuite(projectId)
  const [name, setName] = useState('')

  const add = async (event: React.FormEvent) => {
    event.preventDefault()
    if (name.trim() === '') return
    try {
      await create.mutateAsync({ name: name.trim() })
      toast.success(`Suite "${name.trim()}" created.`)
      setName('')
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The suite could not be created.')
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Suites</DialogTitle>
          <DialogDescription>
            Group tests into suites. Generated tests start in “Generated tests”; move them with the test list or the test
            panel.
          </DialogDescription>
        </DialogHeader>
        {suites.isPending ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : suites.isError ? (
          <p className="text-sm text-destructive">{suites.error.message}</p>
        ) : (
          <ul aria-label="Suites" className="divide-y rounded-lg border">
            {suites.data.map((suite) => (
              <SuiteRow key={suite.id} projectId={projectId} suite={suite} />
            ))}
            {suites.data.length === 0 ? (
              <li className="p-3 text-sm text-muted-foreground">No suites yet; generating tests creates the first one.</li>
            ) : null}
          </ul>
        )}
        <form onSubmit={(event) => void add(event)} className="flex items-end gap-2">
          <div className="flex-1 space-y-1.5">
            <Label htmlFor="new-suite">New suite</Label>
            <Input id="new-suite" value={name} maxLength={100} onChange={(event) => setName(event.target.value)} />
          </div>
          <Button type="submit" disabled={create.isPending || name.trim() === ''}>
            Create
          </Button>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function SuiteRow({ projectId, suite }: { projectId: string; suite: Suite }) {
  const rename = useRenameSuite(projectId)
  const remove = useDeleteSuite(projectId)
  const [editing, setEditing] = useState(false)
  const [name, setName] = useState(suite.name)
  const deletable = suite.kind === 'CUSTOM' && suite.testCount === 0

  const save = async () => {
    try {
      await rename.mutateAsync({ suiteId: suite.id, name: name.trim(), version: suite.version })
      setEditing(false)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The suite could not be renamed.')
    }
  }

  const destroy = async () => {
    try {
      await remove.mutateAsync(suite.id)
      toast.success(`Suite "${suite.name}" deleted.`)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : 'The suite could not be deleted.')
    }
  }

  return (
    <li className="flex flex-wrap items-center gap-2 p-3 text-sm">
      {editing ? (
        <Input
          aria-label={`New name for ${suite.name}`}
          className="h-8 flex-1"
          value={name}
          maxLength={100}
          onChange={(event) => setName(event.target.value)}
        />
      ) : (
        <span className="min-w-0 flex-1 truncate">
          {suite.name}
          <span className="ml-2 text-xs text-muted-foreground">
            {suite.testCount} test{suite.testCount === 1 ? '' : 's'}
            {suite.kind === 'GENERATED' ? ' · generated' : ''}
          </span>
        </span>
      )}
      {editing ? (
        <>
          <Button size="sm" onClick={() => void save()} disabled={rename.isPending || name.trim() === ''}>
            Save
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setEditing(false)}>
            Cancel
          </Button>
        </>
      ) : (
        <>
          <Button size="sm" variant="ghost" onClick={() => setEditing(true)}>
            Rename
          </Button>
          <Button
            size="sm"
            variant="ghost"
            disabled={!deletable || remove.isPending}
            title={deletable ? undefined : suite.kind === 'CUSTOM' ? 'Move its tests to another suite first' : 'The generated suite is kept'}
            onClick={() => void destroy()}
          >
            Delete
          </Button>
        </>
      )}
    </li>
  )
}
