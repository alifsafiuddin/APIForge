import type { FieldValues, Path, UseFormSetError } from 'react-hook-form'

import { ApiError } from '@/api/client'

/**
 * Copies server-side field errors (problem+json `errors[]`) onto the matching form fields.
 * Returns true if at least one error was attached to a field.
 */
export function applyServerFieldErrors<T extends FieldValues>(
  error: unknown,
  fields: readonly Path<T>[],
  setError: UseFormSetError<T>,
): boolean {
  if (!(error instanceof ApiError)) {
    return false
  }
  let applied = false
  for (const fieldError of error.fieldErrors) {
    const field = fields.find((name) => name === fieldError.field)
    if (field) {
      setError(field, { type: 'server', message: fieldError.message })
      applied = true
    }
  }
  return applied
}

/** aria attributes for the control inside a `FormField`. */
export function fieldAria(id: string, error: string | undefined, hint?: string) {
  return {
    id,
    'aria-invalid': error ? true : undefined,
    'aria-describedby': error ? `${id}-error` : hint ? `${id}-hint` : undefined,
  } as const
}
