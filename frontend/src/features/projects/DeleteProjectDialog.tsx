import { useState } from 'react'
import { toast } from 'sonner'

import { FormField } from '@/components/FormField'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { fieldAria } from '@/lib/forms'

import { type Project, useDeleteProject } from './api'

/** Permanent deletion requires typing the project name, because it also removes all history. */
export function DeleteProjectDialog({
  project,
  onOpenChange,
}: {
  project: Project | null
  onOpenChange: (open: boolean) => void
}) {
  return (
    <AlertDialog open={project !== null} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        {/* Content unmounts on close, so the typed confirmation never carries over. */}
        {project ? <DeleteConfirmation project={project} onDone={() => onOpenChange(false)} /> : null}
      </AlertDialogContent>
    </AlertDialog>
  )
}

function DeleteConfirmation({ project, onDone }: { project: Project; onDone: () => void }) {
  const remove = useDeleteProject()
  const [confirmation, setConfirmation] = useState('')
  const matches = confirmation.trim() === project.name

  const confirm = async () => {
    try {
      await remove.mutateAsync(project.id)
      toast.success(`Project "${project.name}" deleted.`)
      onDone()
    } catch {
      // Shown below via remove.error.
    }
  }

  return (
    <>
      <AlertDialogHeader>
        <AlertDialogTitle>Delete “{project.name}”?</AlertDialogTitle>
        <AlertDialogDescription>
          This permanently deletes the project together with its specification versions, environments, tests and
          execution history. This cannot be undone. Archive the project instead if you may need it later.
        </AlertDialogDescription>
      </AlertDialogHeader>
      <FormField id="delete-confirmation" label="Type the project name to confirm">
        <Input
          {...fieldAria('delete-confirmation', undefined)}
          value={confirmation}
          onChange={(event) => setConfirmation(event.target.value)}
          autoComplete="off"
        />
      </FormField>
      {remove.isError ? (
        <p className="text-sm text-destructive" role="alert">
          {remove.error.message}
        </p>
      ) : null}
      <AlertDialogFooter>
        <AlertDialogCancel>Cancel</AlertDialogCancel>
        <Button variant="destructive" disabled={!matches || remove.isPending} onClick={() => void confirm()}>
          {remove.isPending ? 'Deleting…' : 'Delete project'}
        </Button>
      </AlertDialogFooter>
    </>
  )
}
