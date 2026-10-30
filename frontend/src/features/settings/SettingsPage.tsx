import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { useNavigate } from 'react-router'
import { toast } from 'sonner'
import { z } from 'zod'

import { ApiError } from '@/api/client'
import { FormField } from '@/components/FormField'
import { PageHeader } from '@/components/PageHeader'
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { type User, useChangePassword, useCurrentUser, useDeleteAccount, useLogout, useUpdateProfile } from '@/features/auth/api'
import { applyServerFieldErrors, fieldAria } from '@/lib/forms'

/** Problems without field errors (rate limit, backend down) are shown above the form. */
function generalError(error: unknown): string | null {
  return error instanceof ApiError && error.fieldErrors.length === 0 ? error.message : null
}

const profileSchema = z.object({
  displayName: z.string().trim().min(1, 'Enter a display name.').max(100, 'At most 100 characters.'),
})

function ProfileCard({ user }: { user: User }) {
  const update = useUpdateProfile()
  const form = useForm<z.infer<typeof profileSchema>>({
    resolver: zodResolver(profileSchema),
    defaultValues: { displayName: user.displayName },
  })
  const { errors, isSubmitting, isDirty } = form.formState

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      const updated = await update.mutateAsync(values)
      form.reset({ displayName: updated.displayName })
      toast.success('Profile saved.')
    } catch (error) {
      applyServerFieldErrors(error, ['displayName'], form.setError)
    }
  })

  return (
    <Card>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-6">
        <CardHeader>
          <CardTitle>
            <h2>Profile</h2>
          </CardTitle>
          <CardDescription>Your name as shown in the application.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {generalError(update.error) ? (
            <Alert variant="destructive">
              <AlertDescription>{generalError(update.error)}</AlertDescription>
            </Alert>
          ) : null}
          <FormField id="settings-email" label="E-mail" hint="The e-mail address you sign in with cannot be changed.">
            <Input {...fieldAria('settings-email', undefined, 'hint')} value={user.email} readOnly className="bg-muted/50" />
          </FormField>
          <FormField id="settings-display-name" label="Display name" error={errors.displayName?.message}>
            <Input
              {...fieldAria('settings-display-name', errors.displayName?.message)}
              autoComplete="name"
              {...form.register('displayName')}
            />
          </FormField>
        </CardContent>
        <CardFooter className="justify-end">
          <Button type="submit" disabled={!isDirty || isSubmitting}>
            {isSubmitting ? 'Saving…' : 'Save profile'}
          </Button>
        </CardFooter>
      </form>
    </Card>
  )
}

// Mirrors the registration rules for quick feedback; the server remains the authority.
const passwordSchema = z
  .object({
    currentPassword: z.string().min(1, 'Enter your current password.'),
    newPassword: z
      .string()
      .min(12, 'Use at least 12 characters.')
      .max(64, 'Use at most 64 characters.')
      .refine((value) => new TextEncoder().encode(value).length <= 72, 'Too long when encoded (max 72 bytes).'),
    confirmPassword: z.string(),
  })
  .refine((values) => values.newPassword === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords do not match.',
  })
type PasswordValues = z.infer<typeof passwordSchema>

function PasswordCard() {
  const change = useChangePassword()
  const form = useForm<PasswordValues>({
    resolver: zodResolver(passwordSchema),
    defaultValues: { currentPassword: '', newPassword: '', confirmPassword: '' },
  })
  const { errors, isSubmitting } = form.formState

  const onSubmit = form.handleSubmit(async ({ currentPassword, newPassword }) => {
    try {
      await change.mutateAsync({ currentPassword, newPassword })
      form.reset()
      toast.success('Password changed. Other browsers signed in to this account were signed out.')
    } catch (error) {
      applyServerFieldErrors(error, ['currentPassword', 'newPassword'], form.setError)
    }
  })

  return (
    <Card>
      <form onSubmit={(event) => void onSubmit(event)} noValidate className="flex flex-col gap-6">
        <CardHeader>
          <CardTitle>
            <h2>Password</h2>
          </CardTitle>
          <CardDescription>Changing it keeps you signed in here and signs out every other session.</CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {generalError(change.error) ? (
            <Alert variant="destructive">
              <AlertDescription>{generalError(change.error)}</AlertDescription>
            </Alert>
          ) : null}
          <FormField id="current-password" label="Current password" error={errors.currentPassword?.message}>
            <Input
              {...fieldAria('current-password', errors.currentPassword?.message)}
              type="password"
              autoComplete="current-password"
              {...form.register('currentPassword')}
            />
          </FormField>
          <FormField
            id="new-password"
            label="New password"
            error={errors.newPassword?.message}
            hint="12–64 characters. Avoid common passwords and your e-mail address."
          >
            <Input
              {...fieldAria('new-password', errors.newPassword?.message, 'hint')}
              type="password"
              autoComplete="new-password"
              {...form.register('newPassword')}
            />
          </FormField>
          <FormField id="confirm-new-password" label="Confirm new password" error={errors.confirmPassword?.message}>
            <Input
              {...fieldAria('confirm-new-password', errors.confirmPassword?.message)}
              type="password"
              autoComplete="new-password"
              {...form.register('confirmPassword')}
            />
          </FormField>
        </CardContent>
        <CardFooter className="justify-end">
          <Button type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Changing…' : 'Change password'}
          </Button>
        </CardFooter>
      </form>
    </Card>
  )
}

function DeleteAccountDialog({ user, open, onOpenChange }: { user: User; open: boolean; onOpenChange: (open: boolean) => void }) {
  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        {/* Content unmounts on close, so typed values never carry over. */}
        {open ? <DeleteAccountForm user={user} /> : null}
      </AlertDialogContent>
    </AlertDialog>
  )
}

function DeleteAccountForm({ user }: { user: User }) {
  const remove = useDeleteAccount()
  const navigate = useNavigate()
  const [password, setPassword] = useState('')
  const [confirmEmail, setConfirmEmail] = useState('')
  const fieldError = (field: string) =>
    remove.error instanceof ApiError ? remove.error.fieldErrors.find((error) => error.field === field)?.message : undefined
  const matches = confirmEmail.trim().toLowerCase() === user.email.toLowerCase()

  const confirm = async () => {
    try {
      await remove.mutateAsync({ password, confirmEmail: confirmEmail.trim() })
      toast.success('Your account and all its projects were deleted.')
      void navigate('/login', { replace: true })
    } catch {
      // Shown in the form via remove.error.
    }
  }

  return (
    <form
      className="grid gap-4"
      onSubmit={(event) => {
        event.preventDefault()
        void confirm()
      }}
      noValidate
    >
      <AlertDialogHeader>
        <AlertDialogTitle>Delete your account?</AlertDialogTitle>
        <AlertDialogDescription>
          This permanently deletes your account and every project you own, with their specifications, tests,
          environments, secrets and run history. It cannot be undone.
        </AlertDialogDescription>
      </AlertDialogHeader>
      {generalError(remove.error) ? (
        <p className="text-sm text-destructive" role="alert">
          {generalError(remove.error)}
        </p>
      ) : null}
      <FormField id="delete-account-email" label={`Type ${user.email} to confirm`} error={fieldError('confirmEmail')}>
        <Input
          {...fieldAria('delete-account-email', fieldError('confirmEmail'))}
          value={confirmEmail}
          onChange={(event) => setConfirmEmail(event.target.value)}
          autoComplete="off"
        />
      </FormField>
      <FormField id="delete-account-password" label="Password" error={fieldError('password')}>
        <Input
          {...fieldAria('delete-account-password', fieldError('password'))}
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </FormField>
      <AlertDialogFooter>
        <AlertDialogCancel type="button">Cancel</AlertDialogCancel>
        <Button type="submit" variant="destructive" disabled={!matches || password.length === 0 || remove.isPending}>
          {remove.isPending ? 'Deleting…' : 'Delete account'}
        </Button>
      </AlertDialogFooter>
    </form>
  )
}

/** The signed-in user's account: profile, password, sign-out and deletion. */
export function SettingsPage() {
  const user = useCurrentUser()
  const logout = useLogout()
  const navigate = useNavigate()
  const [deleting, setDeleting] = useState(false)

  const signOut = async () => {
    try {
      await logout.mutateAsync()
    } catch {
      toast.error('Sign-out could not be confirmed by the server. Local session data was cleared.')
    }
    void navigate('/login', { replace: true })
  }

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <PageHeader title="Settings" description="Manage your account." />
      {!user.data ? (
        <Skeleton className="h-64" aria-label="Loading account" />
      ) : (
        <>
          <ProfileCard key={user.data.id} user={user.data} />
          <PasswordCard />
          <Card>
            <CardHeader>
              <CardTitle>
                <h2>Session</h2>
              </CardTitle>
              <CardDescription>Sessions end after a period without activity and a fixed time after sign-in (30 minutes and 12 hours by default).</CardDescription>
            </CardHeader>
            <CardFooter className="justify-end">
              <Button variant="outline" onClick={() => void signOut()} disabled={logout.isPending}>
                {logout.isPending ? 'Signing out…' : 'Sign out'}
              </Button>
            </CardFooter>
          </Card>
          <Card className="border-destructive/40">
            <CardHeader>
              <CardTitle>
                <h2>Delete account</h2>
              </CardTitle>
              <CardDescription>Removes your account and all projects you own. This cannot be undone.</CardDescription>
            </CardHeader>
            <CardFooter className="justify-end">
              <Button variant="destructive" onClick={() => setDeleting(true)}>
                Delete account…
              </Button>
            </CardFooter>
          </Card>
          <DeleteAccountDialog user={user.data} open={deleting} onOpenChange={setDeleting} />
        </>
      )}
    </div>
  )
}
