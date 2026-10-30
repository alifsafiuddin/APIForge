/**
 * JSON paths in the format the backend reports body findings in (com.apiforge.schema.JsonPaths):
 * `$`, `$.items[2].price`, `$['first name']`. Object keys made of digits are written like array
 * indexes, as the backend does.
 */
export type PathToken = string | number

const IDENTIFIER = /^[A-Za-z_$][A-Za-z0-9_$]*$/
const INDEX = /^\d{1,9}$/

export function childPath(parent: string, key: PathToken): string {
  const token = String(key)
  if (INDEX.test(token)) return `${parent}[${token}]`
  if (IDENTIFIER.test(token)) return `${parent}.${token}`
  return `${parent}['${token.replaceAll('\\', '\\\\').replaceAll("'", "\\'")}']`
}

export function toPath(tokens: PathToken[]): string {
  return tokens.reduce<string>((path, token) => childPath(path, token), '$')
}

/** The tokens of a path, or null when it is not in the backend's format. */
export function parsePath(path: string): PathToken[] | null {
  if (!path.startsWith('$')) return null
  const tokens: PathToken[] = []
  let index = 1
  while (index < path.length) {
    if (path[index] === '.') {
      const match = /^[A-Za-z_$][A-Za-z0-9_$]*/.exec(path.slice(index + 1))
      if (!match) return null
      tokens.push(match[0])
      index += 1 + match[0].length
    } else if (path.startsWith("['", index)) {
      let key = ''
      index += 2
      while (index < path.length && path[index] !== "'") {
        if (path[index] === '\\' && index + 1 < path.length) index += 1
        key += path[index]
        index += 1
      }
      if (!path.startsWith("']", index)) return null
      tokens.push(key)
      index += 2
    } else if (path[index] === '[') {
      const end = path.indexOf(']', index)
      const token = end < 0 ? '' : path.slice(index + 1, end)
      if (!INDEX.test(token)) return null
      tokens.push(Number(token))
      index = end + 1
    } else {
      return null
    }
  }
  return tokens
}
