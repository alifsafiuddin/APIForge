/**
 * Returns `target` only if it is a path within this app; anything else (absolute URLs,
 * protocol-relative `//host`, backslash tricks) falls back to `/`. Prevents open redirects
 * through the `next` query parameter.
 */
export function safeRedirectPath(target: string | null | undefined): string {
  if (!target || !target.startsWith('/') || target.startsWith('//') || target.startsWith('/\\')) {
    return '/'
  }
  if (target.startsWith('/login') || target.startsWith('/register')) {
    return '/'
  }
  return target
}

export function loginPathFor(location: { pathname: string; search: string }): string {
  const next = `${location.pathname}${location.search}`
  return next === '/' ? '/login' : `/login?next=${encodeURIComponent(next)}`
}
