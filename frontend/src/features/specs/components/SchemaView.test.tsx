import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'

import { SchemaView } from './SchemaView'
import { refName, typeLabel } from './schema-utils'

const components = {
  Order: {
    type: 'object',
    required: ['id', 'lines'],
    properties: {
      id: { type: 'string', format: 'uuid' },
      status: { $ref: '#/components/schemas/Status' },
      lines: { type: 'array', minItems: 1, items: { $ref: '#/components/schemas/Line' } },
      note: { type: 'string', nullable: true, maxLength: 500 },
    },
  },
  Status: { type: 'string', enum: ['PENDING', 'SHIPPED'] },
  Line: { type: 'object', properties: { product: { $ref: '#/components/schemas/Category' } } },
  Category: { type: 'object', properties: { parent: { $ref: '#/components/schemas/Category' } } },
}

describe('SchemaView', () => {
  it('expands references and shows required, nullable, enum and constraint details', () => {
    render(<SchemaView schema={{ $ref: '#/components/schemas/Order' }} components={components} />)

    const properties = screen.getAllByRole('list', { name: 'Properties' })[0]!
    expect(within(properties).getByText('id')).toBeInTheDocument()
    expect(within(properties).getAllByText('required')).toHaveLength(2)
    expect(within(properties).getByText('string (uuid)')).toBeInTheDocument()
    expect(within(properties).getByText('nullable')).toBeInTheDocument()
    expect(within(properties).getByText('max length: 500')).toBeInTheDocument()
    expect(within(properties).getByText('array of Line')).toBeInTheDocument()
    expect(within(properties).getByText('min items: 1')).toBeInTheDocument()
    const allowed = screen.getByLabelText('Allowed values')
    expect(within(allowed).getByText('"PENDING"')).toBeInTheDocument()
    expect(within(allowed).getByText('"SHIPPED"')).toBeInTheDocument()
  })

  it('collapses deep references and expands them on demand', async () => {
    const user = userEvent.setup()
    render(<SchemaView schema={{ $ref: '#/components/schemas/Order' }} components={components} />)

    const category = screen.getByRole('button', { name: 'Category' })
    expect(category).toHaveAttribute('aria-expanded', 'false')
    await user.click(category)
    expect(category).toHaveAttribute('aria-expanded', 'true')
  })

  it('stops at recursive references instead of looping', async () => {
    const user = userEvent.setup()
    render(<SchemaView schema={{ $ref: '#/components/schemas/Category' }} components={components} />)

    expect(screen.getByText('(recursive reference)')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Category' }))
    expect(screen.queryByText('(recursive reference)')).not.toBeInTheDocument()
  })

  it('reports references that cannot be resolved', () => {
    render(<SchemaView schema={{ $ref: '#/components/schemas/Missing' }} components={components} />)

    expect(screen.getByText('Missing (not found)')).toBeInTheDocument()
  })

  it('renders OpenAPI 3.1 type arrays as nullable', () => {
    render(<SchemaView schema={{ type: ['string', 'null'] }} components={{}} />)

    expect(screen.getByText('string | null')).toBeInTheDocument()
    expect(screen.getByText('nullable')).toBeInTheDocument()
  })

  it('renders composition members', () => {
    render(
      <SchemaView
        schema={{ allOf: [{ $ref: '#/components/schemas/Status' }, { type: 'object', properties: { extra: { type: 'integer' } } }] }}
        components={components}
      />,
    )

    expect(screen.getByText('All of:')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Status' })).toBeInTheDocument()
    expect(screen.getByText('extra')).toBeInTheDocument()
  })

  it('treats description text as text, not markup', () => {
    render(<SchemaView schema={{ type: 'string', description: '<img src=x onerror=alert(1)>' }} components={{}} />)

    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(document.querySelector('img')).toBeNull()
  })
})

describe('schema utilities', () => {
  it('derives readable type labels', () => {
    expect(typeLabel({ type: 'array', items: { $ref: '#/components/schemas/Pet' } })).toBe('array of Pet')
    expect(typeLabel({ type: 'integer', format: 'int64' })).toBe('integer (int64)')
    expect(typeLabel({ oneOf: [] })).toBe('one of')
    expect(typeLabel(undefined)).toBe('any')
  })

  it('decodes JSON pointer escapes in reference names', () => {
    expect(refName('#/components/schemas/a~1b~0c')).toBe('a/b~c')
  })
})
