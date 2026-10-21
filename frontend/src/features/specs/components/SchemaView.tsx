import { ChevronDown, ChevronRight } from 'lucide-react'
import { type ReactNode, useState } from 'react'

import type { JsonObject } from '../api'
import { isObject, refName, resolveSchemaRef, typeLabel } from './schema-utils'

type SchemaProps = {
  schema: unknown
  components: JsonObject
  /** References already being expanded on this branch; used to stop on recursion. */
  refStack?: string[]
  depth?: number
}

/** References are expanded automatically up to this many levels deep; deeper ones on click. */
const AUTO_EXPAND_REFS = 2
const CONSTRAINT_KEYS: [string, string][] = [
  ['minimum', 'min'],
  ['maximum', 'max'],
  ['exclusiveMinimum', 'exclusive min'],
  ['exclusiveMaximum', 'exclusive max'],
  ['multipleOf', 'multiple of'],
  ['minLength', 'min length'],
  ['maxLength', 'max length'],
  ['pattern', 'pattern'],
  ['minItems', 'min items'],
  ['maxItems', 'max items'],
  ['minProperties', 'min properties'],
  ['maxProperties', 'max properties'],
]

function Tag({ children, tone = 'muted' }: { children: ReactNode; tone?: 'muted' | 'warn' | 'required' }) {
  const styles = {
    muted: 'bg-muted text-muted-foreground',
    warn: 'bg-amber-100 text-amber-900',
    required: 'bg-red-50 text-red-700',
  }
  return <span className={`rounded px-1.5 py-0.5 text-[0.7rem] font-medium ${styles[tone]}`}>{children}</span>
}

function formatValue(value: unknown): string {
  const text = JSON.stringify(value) ?? String(value)
  return text.length > 120 ? `${text.slice(0, 120)}…` : text
}

/** Recursive, read-only rendering of an OpenAPI/JSON schema. All content is rendered as text. */
export function SchemaView({ schema, components, refStack = [], depth = 0 }: SchemaProps) {
  if (!isObject(schema)) {
    return <span className="text-xs text-muted-foreground">any value</span>
  }
  if (typeof schema.$ref === 'string') {
    return <RefView ref_={schema.$ref} components={components} refStack={refStack} depth={depth} />
  }
  return <InlineSchema schema={schema} components={components} refStack={refStack} depth={depth} />
}

function RefView({ ref_, components, refStack, depth }: { ref_: string; components: JsonObject; refStack: string[]; depth: number }) {
  const [open, setOpen] = useState(refStack.length < AUTO_EXPAND_REFS)
  const name = refName(ref_)
  const target = resolveSchemaRef(ref_, components)
  if (refStack.includes(ref_)) {
    return (
      <span className="font-mono text-xs">
        {name} <span className="text-muted-foreground">(recursive reference)</span>
      </span>
    )
  }
  if (!target) {
    return <span className="font-mono text-xs text-muted-foreground">{name} (not found)</span>
  }
  return (
    <div>
      <button
        type="button"
        onClick={() => setOpen(!open)}
        className="inline-flex items-center gap-1 rounded font-mono text-xs font-medium text-primary hover:underline focus-visible:ring-2 focus-visible:ring-ring focus-visible:outline-none"
        aria-expanded={open}
      >
        {open ? <ChevronDown className="size-3.5" aria-hidden="true" /> : <ChevronRight className="size-3.5" aria-hidden="true" />}
        {name}
      </button>
      {open ? (
        <div className="mt-1">
          <InlineSchema schema={target} components={components} refStack={[...refStack, ref_]} depth={depth + 1} />
        </div>
      ) : null}
    </div>
  )
}

function InlineSchema({ schema, components, refStack, depth }: { schema: JsonObject; components: JsonObject; refStack: string[]; depth: number }) {
  const properties = isObject(schema.properties) ? schema.properties : undefined
  const required = Array.isArray(schema.required) ? schema.required.map(String) : []
  const composition = (['allOf', 'oneOf', 'anyOf'] as const).filter((key) => Array.isArray(schema[key]))
  const constraints = CONSTRAINT_KEYS.filter(([key]) => schema[key] !== undefined)
  const nullable = schema.nullable === true || (Array.isArray(schema.type) && schema.type.includes('null'))

  return (
    <div className="space-y-1.5 text-sm">
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="font-mono text-xs">{typeLabel(schema)}</span>
        {nullable ? <Tag>nullable</Tag> : null}
        {schema.readOnly === true ? <Tag>read-only</Tag> : null}
        {schema.writeOnly === true ? <Tag>write-only</Tag> : null}
        {schema.deprecated === true ? <Tag tone="warn">deprecated</Tag> : null}
        {schema.uniqueItems === true ? <Tag>unique items</Tag> : null}
        {constraints.map(([key, label]) => (
          <Tag key={key}>
            {label}: {formatValue(schema[key])}
          </Tag>
        ))}
        {schema.default !== undefined ? <Tag>default: {formatValue(schema.default)}</Tag> : null}
      </div>
      {typeof schema.description === 'string' ? (
        <p className="text-xs whitespace-pre-wrap text-muted-foreground">{schema.description}</p>
      ) : null}
      {Array.isArray(schema.enum) ? (
        <div className="flex flex-wrap items-center gap-1" aria-label="Allowed values">
          <span className="text-xs text-muted-foreground">One of:</span>
          {schema.enum.map((value, index) => (
            <code key={index} className="rounded border bg-background px-1 text-xs">
              {formatValue(value)}
            </code>
          ))}
        </div>
      ) : null}
      {schema.example !== undefined ? (
        <p className="text-xs text-muted-foreground">
          Example: <code>{formatValue(schema.example)}</code>
        </p>
      ) : null}
      {properties ? (
        <ul className="space-y-2 border-l pl-3" aria-label="Properties">
          {Object.entries(properties).map(([name, property]) => (
            <li key={name}>
              <div className="flex flex-wrap items-baseline gap-1.5">
                <span className="font-mono text-xs font-semibold">{name}</span>
                {required.includes(name) ? <Tag tone="required">required</Tag> : null}
              </div>
              <div className="mt-0.5">
                <SchemaView schema={property} components={components} refStack={refStack} depth={depth + 1} />
              </div>
            </li>
          ))}
        </ul>
      ) : null}
      {schema.items !== undefined ? (
        <div className="border-l pl-3">
          <span className="text-xs text-muted-foreground">Items:</span>
          <SchemaView schema={schema.items} components={components} refStack={refStack} depth={depth + 1} />
        </div>
      ) : null}
      {isObject(schema.additionalProperties) ? (
        <div className="border-l pl-3">
          <span className="text-xs text-muted-foreground">Additional properties:</span>
          <SchemaView schema={schema.additionalProperties} components={components} refStack={refStack} depth={depth + 1} />
        </div>
      ) : null}
      {composition.map((key) => (
        <div key={key} className="border-l pl-3">
          <span className="text-xs text-muted-foreground">{key === 'allOf' ? 'All of:' : key === 'oneOf' ? 'One of:' : 'Any of:'}</span>
          <ol className="space-y-1.5">
            {(schema[key] as unknown[]).map((member, index) => (
              <li key={index}>
                <SchemaView schema={member} components={components} refStack={refStack} depth={depth + 1} />
              </li>
            ))}
          </ol>
        </div>
      ))}
    </div>
  )
}
