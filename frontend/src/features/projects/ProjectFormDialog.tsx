import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'

import { ApiError } from '@/api/client'
import { FormField } from '@/components/FormField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
import { applyServerFieldErrors, fieldAria } from '@/lib/forms'

import { type Project, useCreateProject, useUpdateProject } from './api'

/** Mirrors the backend rule: no ASCII control characters (including CR/LF) in names. */
function hasControlCharacters(value: string): boolean {
  return [...value].some((character) => {
    const code = character.codePointAt(0) ?? 0
    return code < 0x20 || code === 0x7f
  })
}

const projectFormSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, 'Enter a project name.')
    .max(100, 'At most 100 characters.')
    .refine((value) => !hasControlCharacters(value), 'Must not contain control characters.'),
  description: z.string().max(2000, 'At most 2000 characters.'),
})
type ProjectFormValues = z.infer<typeof projectFormSchema>

/** Create a project, or edit one when `project` is given. */
export function ProjectFormDialog({
  open,
  onOpenChange,
  project,
}: {
  open: boolean
  onOpenChange: (open: boolean) => void
  project?: Project | undefined
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        {/* Content unmounts when the dialog closes, so the form starts fresh on every open. */}
        <ProjectForm project={project} onDone={() => onOpenChange(false)} />
      </DialogContent>
    </Dialog>
  )
}

function ProjectForm({ project, onDone }: { project: Project | undefined; onDone: () => void }) {
  const create = useCreateProject()
  const update = useUpdateProject()
  const editing = project !== undefined
  const mutation = editing ? update : create
  const form = useForm<ProjectFormValues>({
    resolver: zodResolver(projectFormSchema),
    defaultValues: { name: project?.name ?? '', description: project?.description ?? '' },
  })
  const { errors, isSubmitting } = form.formState

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      if (project) {
        await update.mutateAsync({ id: project.id, version: project.version, ...values })
        toast.success('Project updated.')
      } else {
        await create.mutateAsync(values)
        toast.success(`Project "${values.name}" created.`)
      }
      onDone()
    } catch (error) {
      if (error instanceof ApiError && error.code === 'DUPLICATE_NAME') {
        form.setError('name', { type: 'server', message: error.message })
        return
      }
      applyServerFieldErrors(error, ['name', 'description'], form.setError)
    }
  })

  const generalError =
    mutation.error instanceof ApiError &&
    mutation.error.code !== 'DUPLICATE_NAME' &&
    mutation.error.fieldErrors.length === 0
      ? mutation.error
      : null

  return (
    <form onSubmit={(event) => void onSubmit(event)} className="space-y-4" noValidate>
      <DialogHeader>
        <DialogTitle>{editing ? 'Edit project' : 'New project'}</DialogTitle>
        <DialogDescription>
          {editing
            ? 'Rename the project or update its description.'
            : 'Create one project for each API you want to test.'}
        </DialogDescription>
      </DialogHeader>
      {generalError ? (
        <Alert variant="destructive">
          <AlertDescription>
            {generalError.code === 'STALE_VERSION'
              ? 'This project was changed elsewhere. Close the dialog to load the latest version, then try again.'
              : generalError.message}
          </AlertDescription>
        </Alert>
      ) : null}
      <FormField id="project-name" label="Name" error={errors.name?.message}>
        <Input {...fieldAria('project-name', errors.name?.message)}{...form.register('name')} />
      </FormField>
      <FormField id="project-description" label="Description (optional)" error={errors.description?.message}>
        <Textarea
          {...fieldAria('project-description', errors.description?.message)}
          rows={4}
          {...form.register('description')}
        />
      </FormField>
      <DialogFooter>
        <Button type="button" variant="outline" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={isSubmitting}>
          {isSubmitting ? 'Saving…' : editing ? 'Save changes' : 'Create project'}
        </Button>
      </DialogFooter>
    </form>
  )
}
