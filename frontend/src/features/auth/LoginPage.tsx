import { zodResolver } from '@hookform/resolvers/zod'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { z } from 'zod'

import { ApiError } from '@/api/client'
import { FormField } from '@/components/FormField'
import { Alert, AlertDescription } from '@/components/ui/alert'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { applyServerFieldErrors, fieldAria } from '@/lib/forms'

import { useLogin } from './api'
import { AuthLayout } from './AuthLayout'
import { safeRedirectPath } from './redirect'

const loginSchema = z.object({
  email: z.string().trim().min(1, 'Enter your e-mail address.'),
  password: z.string().min(1, 'Enter your password.'),
})
type LoginValues = z.infer<typeof loginSchema>

export function LoginPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const login = useLogin()
  const form = useForm<LoginValues>({ resolver: zodResolver(loginSchema), defaultValues: { email: '', password: '' } })
  const { errors, isSubmitting } = form.formState

  const onSubmit = form.handleSubmit(async (values) => {
    try {
      await login.mutateAsync(values)
      void navigate(safeRedirectPath(searchParams.get('next')), { replace: true })
    } catch (error) {
      applyServerFieldErrors(error, ['email', 'password'], form.setError)
    }
  })

  const serverError = login.error instanceof ApiError && login.error.fieldErrors.length === 0 ? login.error : null

  return (
    <AuthLayout
      title="Sign in"
      description="Sign in to manage your API projects."
      footer={
        <span>
          No account yet?{' '}
          <Link to="/register" className="font-medium text-foreground underline-offset-4 hover:underline">
            Create one
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
            autoComplete="username"
            {...form.register('email')}
          />
        </FormField>
        <FormField id="password" label="Password" error={errors.password?.message}>
          <Input
            {...fieldAria('password', errors.password?.message)}
            type="password"
            autoComplete="current-password"
            {...form.register('password')}
          />
        </FormField>
        <Button type="submit" className="w-full" disabled={isSubmitting}>
          {isSubmitting ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </AuthLayout>
  )
}
