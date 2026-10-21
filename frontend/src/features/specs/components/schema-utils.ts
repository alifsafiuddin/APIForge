import type { JsonObject } from '../api'

export function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function refName(ref: string): string {
  return decodeURIComponent(ref.split('/').pop() ?? ref)
    .replace(/~1/g, '/')
    .replace(/~0/g, '~')
}

/** Resolves "#/components/schemas/Name" against the version's component schemas. */
export function resolveSchemaRef(ref: string, components: JsonObject): JsonObject | undefined {
  const prefix = '#/components/schemas/'
  if (!ref.startsWith(prefix)) {
    return undefined
  }
  const target = components[refName(ref)]
  return isObject(target) ? target : undefined
}

/** A short type label such as "string (uuid)", "array of Order", "integer | null". */
export function typeLabel(schema: unknown): string {
  if (!isObject(schema)) {
    return 'any'
  }
  if (typeof schema.$ref === 'string') {
    return refName(schema.$ref)
  }
  const types = Array.isArray(schema.type) ? schema.type.map(String) : typeof schema.type === 'string' ? [schema.type] : []
  let label = types.length > 0 ? types.join(' | ') : schema.allOf ? 'all of' : schema.oneOf ? 'one of' : schema.anyOf ? 'any of' : schema.properties ? 'object' : 'any'
  if (label === 'array' && schema.items !== undefined) {
    label = `array of ${typeLabel(schema.items)}`
  }
  if (typeof schema.format === 'string') {
    label += ` (${schema.format})`
  }
  return label
}
