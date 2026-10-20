import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router'
import { z } from 'zod'

import { ApiError } from '@/api/client'
import { FormField } from '@/components/FormField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { applyServerFieldErrors, fieldAria } from '@/lib/forms'

import { useRegister } from './api'
import { AuthLayout } from './AuthLayout'

// Mirrors the server rules for quick feedback; the server remains the authority
// (it also rejects common passwords and passwords containing the e-mail address).
const registerSchema = z
  .object({
    email: z.email('Enter a valid e-mail address.').max(254),
    displayName: z.string().trim().min(1, 'Enter a display name.').max(100, 'At most 100 characters.'),
    password: z
      .string()
      .min(12, 'Use at least 12 characters.')
      .max(64, 'Use at most 64 characters.')
      .refine((value) => new TextEncoder().encode(value).length <= 72, 'Too long when encoded (max 72 bytes).'),
    confirmPassword: z.string(),
  })
  .refine((values) => values.password === values.confirmPassword, {
    path: ['confirmPassword'],
    message: 'Passwords do not match.',
  })
type RegisterValues = z.infer<typeof registerSchema>

const SERVER_FIELDS = ['email', 'displayName', 'password'] as const

export function RegisterPage() {
  const navigate = useNavigate()
  const register = useRegister()
  const form = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
    defaultValues: { email: '', displayName: '', password: '', confirmPassword: '' },
  })
  const { errors, isSubmitting } = form.formState

  const onSubmit = form.handleSubmit(async ({ email, displayName, password }) => {
    try {
      await register.mutateAsync({ email: email.trim(), displayName, password })
      void navigate('/', { replace: true })
    } catch (error) {
      if (error instanceof ApiError && error.code === 'EMAIL_TAKEN') {
        form.setError('email', { type: 'server', message: error.message })
        return
      }
      applyServerFieldErrors(error, SERVER_FIELDS, form.setError)
    }
  })

  const serverError =
    register.error instanceof ApiError && register.error.fieldErrors.length === 0 && register.error.code !== 'EMAIL_TAKEN'
      ? register.error
      : null

  return (
    <AuthLayout
      title="Create your account"
      description="Projects, tests and history are private to your account."
      footer={
        <span>
          Already registered?{' '}
          <Link to="/login" className="font-medium text-foreground underline-offset-4 hover:underline">
            Sign in
          </Link>
        </span>
      }
    >
      <form onSubmit={(event) => void onSubmit(event)} className="space-y-4" noValidate>
        {serverError ? (
          <Alert variant="destructive">
            <AlertDescription>{serverError.message}</AlertDescription>
          </Alert>
        ) : null}
        <FormField id="email" label="E-mail" error={errors.email?.message}>
          <Input
            {...fieldAria('email', errors.email?.message)}
            type="email"
            autoComplete="email"
            {...form.register('email')}
          />
        </FormField>
        <FormField id="displayName" label="Display name" error={errors.displayName?.message}>
          <Input
            {...fieldAria('displayName', errors.displayName?.message)}
            autoComplete="name"
            {...form.register('displayName')}
          />
        </FormField>
        <FormField
          id="password"
          label="Password"
          error={errors.password?.message}
          hint="12–64 characters. Avoid common passwords and your e-mail address."
        >
          <Input
            {...fieldAria('password', errors.password?.message, '12–64 characters')}
            type="password"
            autoComplete="new-password"
            {...form.register('password')}
          />
        </FormField>
        <FormField id="confirmPassword" label="Confirm password" error={errors.confirmPassword?.message}>
          <Input
            {...fieldAria('confirmPassword', errors.confirmPassword?.message)}
            type="password"
            autoComplete="new-password"
            {...form.register('confirmPassword')}
          />
        </FormField>
        <Button type="submit" className="w-full" disabled={isSubmitting}>
          {isSubmitting ? 'Creating account…' : 'Create account'}
        </Button>
      </form>
    </AuthLayout>
  )
}
