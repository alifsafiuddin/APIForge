import { describe, expect, it } from 'vitest'

import { loginPathFor, safeRedirectPath } from './redirect'

describe('safeRedirectPath', () => {
  it.each([
    ['/projects', '/projects'],
    ['/projects?archived=true', '/projects?archived=true'],
  ])('keeps in-app path %s', (input, expected) => {
    expect(safeRedirectPath(input)).toBe(expected)
  })

  it.each([
    null,
    '',
    'https://evil.example/phish',
    '//evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
    '/login',
    '/register?next=/x',
  ])('falls back to / for unsafe or pointless target %s', (input) => {
    expect(safeRedirectPath(input)).toBe('/')
  })
})

describe('loginPathFor', () => {
  it('remembers where the user was going', () => {
    expect(loginPathFor({ pathname: '/projects', search: '?archived=true' })).toBe(
      '/login?next=%2Fprojects%3Farchived%3Dtrue',
    )
  })

  it('omits next for the home page', () => {
    expect(loginPathFor({ pathname: '/', search: '' })).toBe('/login')
  })
})
