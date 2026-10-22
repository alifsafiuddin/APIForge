import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { z } from 'zod'

import { ApiError, apiRequest, noContent } from '@/api/client'

export const userSchema = z.object({
  id: z.string(),
  email: z.string(),
  displayName: z.string(),
})
export type User = z.infer<typeof userSchema>

export const authKeys = {
  me: ['auth', 'me'] as const,
}

/**
 * The signed-in user, or `null` when there is no session. Other failures (backend down)
 * stay errors so the UI can tell "signed out" apart from "cannot tell".
 */
export function useCurrentUser() {
  return useQuery({
    queryKey: authKeys.me,
    queryFn: async ({ signal }): Promise<User | null> => {
      try {
        return await apiRequest('/api/v1/auth/me', userSchema, { signal })
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
          return null
        }
        throw error
      }
    },
    staleTime: 5 * 60_000,
    retry: false,
  })
}

export type LoginInput = { email: string; password: string }
export type RegisterInput = { email: string; password: string; displayName: string }

/** Starting a session as a (possibly different) user: drop everything cached for the previous one. */
function useStartSession() {
  const queryClient = useQueryClient()
  return (user: User) => {
    queryClient.clear()
    queryClient.setQueryData(authKeys.me, user)
  }
}

export function useLogin() {
  const startSession = useStartSession()
  return useMutation({
    mutationFn: (input: LoginInput) => apiRequest('/api/v1/auth/login', userSchema, { method: 'POST', body: input }),
    onSuccess: startSession,
  })
}

export function useRegister() {
  const startSession = useStartSession()
  return useMutation({
    mutationFn: (input: RegisterInput) =>
      apiRequest('/api/v1/auth/register', userSchema, { method: 'POST', body: input }),
    onSuccess: startSession,
  })
}

export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => apiRequest('/api/v1/auth/logout', noContent, { method: 'POST' }),
    // Even if the request fails, forget local data: the user asked to leave.
    onSettled: () => {
      queryClient.clear()
      queryClient.setQueryData(authKeys.me, null)
    },
  })
}

export function useUpdateProfile() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { displayName: string }) =>
      apiRequest('/api/v1/auth/me', userSchema, { method: 'PATCH', body: input }),
    onSuccess: (user) => queryClient.setQueryData(authKeys.me, user),
  })
}

/** Changes the password. This session continues; every other session of the account ends. */
export function useChangePassword() {
  return useMutation({
    mutationFn: (input: { currentPassword: string; newPassword: string }) =>
      apiRequest('/api/v1/auth/password', noContent, { method: 'POST', body: input }),
  })
}

/** Deletes the account and all its projects; the session ends with it. */
export function useDeleteAccount() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (input: { password: string; confirmEmail: string }) =>
      apiRequest('/api/v1/auth/me', noContent, { method: 'DELETE', body: input }),
    onSuccess: () => {
      queryClient.clear()
      queryClient.setQueryData(authKeys.me, null)
    },
  })
}
