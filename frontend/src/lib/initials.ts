/** "Ada Lovelace" → "AL", "Ada" → "AD". */
export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean)
  const first = parts[0] ?? '?'
  const last = parts.length > 1 ? (parts[parts.length - 1] ?? '') : ''
  return (first.charAt(0) + (last ? last.charAt(0) : first.charAt(1))).toUpperCase()
}
