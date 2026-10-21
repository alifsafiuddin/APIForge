// Warehouse Lab API: a local, authorized target for trying APIForge's execution engine.
//
//   node samples/warehouse-lab-server.mjs          (listens on 127.0.0.1:8090 only)
//
// It implements samples/warehouse-lab-openapi.yaml with request validation and API-key
// authentication (header X-API-Key). The key is read from WAREHOUSE_LAB_API_KEY and defaults to
// the demo value "lab-demo-key"; it protects nothing but this sample.
//
// One deviation from the contract is deliberate, so a run shows a real failure:
//   order line quantities above 999 are accepted (the contract says maximum 999).
//
// Diagnostic endpoints (not in the contract) misbehave on purpose:
//   GET /lab/echo                 echoes the received headers and query string
//   GET /lab/slow?ms=3000         answers after a delay (timeouts)
//   GET /lab/malformed            Content-Type JSON, invalid JSON body
//   GET /lab/status/{code}        answers with that status
//   GET /lab/redirect?to=URL      302 to URL (redirect restrictions)
//   GET /lab/redirect-metadata    302 to the cloud metadata address
//   GET /lab/large?kb=4096        a large text body (size limits)
// Nothing here is meant to be exposed beyond the local machine.

import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'

const HOST = '127.0.0.1'
const PORT = Number(process.env.WAREHOUSE_LAB_PORT ?? 8090)
const API_KEY = process.env.WAREHOUSE_LAB_API_KEY ?? 'lab-demo-key'

// ---- schemas (mirroring samples/warehouse-lab-openapi.yaml) -----------------------------------

const CATEGORY = { type: 'string', enum: ['tools', 'garden', 'kitchen'] }
const DIMENSIONS = {
  type: 'object',
  required: ['widthCm', 'heightCm'],
  properties: {
    widthCm: { type: 'number', minimum: 0.1, maximum: 500 },
    heightCm: { type: 'number', exclusiveMinimum: 0, maximum: 500 },
  },
}
const NEW_PRODUCT = {
  type: 'object',
  required: ['name', 'sku', 'price', 'category'],
  properties: {
    name: { type: 'string', minLength: 3, maxLength: 80 },
    sku: { type: 'string', pattern: '^[A-Z]{3}-[0-9]{4}$' },
    price: { type: 'number', exclusiveMinimum: 0, maximum: 100000, multipleOf: 0.01 },
    category: CATEGORY,
    tags: { type: 'array', maxItems: 5, uniqueItems: true, items: { type: 'string', minLength: 1, maxLength: 20 } },
    stock: { type: 'integer', minimum: 0, maximum: 10000 },
    dimensions: DIMENSIONS,
    active: { type: 'boolean' },
    discontinuedAt: { type: ['string', 'null'] },
  },
}
const PRODUCT_PATCH = {
  type: 'object',
  minProperties: 1,
  additionalProperties: false,
  properties: {
    name: { type: 'string', minLength: 3, maxLength: 80 },
    price: { type: 'number', exclusiveMinimum: 0, maximum: 100000 },
    stock: { type: 'integer', minimum: 0, maximum: 10000 },
  },
}
const ADDRESS = {
  type: 'object',
  required: ['kind', 'street', 'city', 'postalCode'],
  properties: {
    kind: { type: 'string', enum: ['address'] },
    street: { type: 'string', minLength: 1, maxLength: 100 },
    city: { type: 'string', minLength: 1, maxLength: 60 },
    postalCode: { type: 'string', pattern: '^[0-9]{5}$' },
  },
}
const PICKUP = {
  type: 'object',
  required: ['kind', 'pointId'],
  properties: { kind: { type: 'string', enum: ['pickup'] }, pointId: { type: 'string', minLength: 6, maxLength: 6 } },
}
const NEW_ORDER = {
  type: 'object',
  required: ['lines', 'delivery'],
  properties: {
    lines: {
      type: 'array',
      minItems: 1,
      maxItems: 20,
      items: {
        type: 'object',
        required: ['productId', 'quantity'],
        // Deliberate deviation: the contract says maximum 999.
        properties: { productId: { type: 'integer', minimum: 1 }, quantity: { type: 'integer', minimum: 1, maximum: 9999 } },
      },
    },
    delivery: { oneOf: [ADDRESS, PICKUP] },
    couponCode: { type: 'string', pattern: '^[A-Z]{4}-[0-9]{4}$' },
    notes: { type: ['string', 'null'], maxLength: 500 },
    express: { type: 'boolean' },
    requestedDeliveryDate: { type: 'string', pattern: '^\\d{4}-\\d{2}-\\d{2}$' },
  },
}

/** A small validator for the keywords used above; returns the first problem or null. */
function validate(value, schema, path = 'body') {
  const types = schema.type === undefined ? [] : [].concat(schema.type)
  const actual = value === null ? 'null' : Array.isArray(value) ? 'array' : typeof value === 'number'
    ? (Number.isInteger(value) ? 'integer' : 'number') : typeof value
  if (types.length > 0 && !types.includes(actual) && !(actual === 'integer' && types.includes('number'))) {
    return `${path} must be ${types.join(' or ')}`
  }
  if (value === null) return null
  if (schema.enum && !schema.enum.includes(value)) return `${path} must be one of ${schema.enum.join(', ')}`
  if (typeof value === 'number') {
    if (schema.minimum !== undefined && value < schema.minimum) return `${path} must be >= ${schema.minimum}`
    if (schema.maximum !== undefined && value > schema.maximum) return `${path} must be <= ${schema.maximum}`
    if (schema.exclusiveMinimum !== undefined && value <= schema.exclusiveMinimum) return `${path} must be > ${schema.exclusiveMinimum}`
    if (schema.multipleOf !== undefined && Math.abs(Math.round(value / schema.multipleOf) * schema.multipleOf - value) > 1e-9) {
      return `${path} must be a multiple of ${schema.multipleOf}`
    }
  }
  if (typeof value === 'string') {
    const length = [...value].length
    if (schema.minLength !== undefined && length < schema.minLength) return `${path} is too short`
    if (schema.maxLength !== undefined && length > schema.maxLength) return `${path} is too long`
    if (schema.pattern && !new RegExp(schema.pattern).test(value)) return `${path} has an invalid format`
  }
  if (Array.isArray(value)) {
    if (schema.minItems !== undefined && value.length < schema.minItems) return `${path} has too few items`
    if (schema.maxItems !== undefined && value.length > schema.maxItems) return `${path} has too many items`
    if (schema.uniqueItems && new Set(value.map((item) => JSON.stringify(item))).size !== value.length) return `${path} has duplicates`
    if (schema.items) {
      for (let index = 0; index < value.length; index++) {
        const problem = validate(value[index], schema.items, `${path}[${index}]`)
        if (problem) return problem
      }
    }
  }
  if (actual === 'object') {
    for (const name of schema.required ?? []) if (!(name in value)) return `${path}.${name} is required`
    if (schema.minProperties !== undefined && Object.keys(value).length < schema.minProperties) return `${path} is empty`
    for (const [name, member] of Object.entries(value)) {
      const propertySchema = schema.properties?.[name]
      if (!propertySchema) {
        if (schema.additionalProperties === false) return `${path}.${name} is not allowed`
        continue
      }
      const problem = validate(member, propertySchema, `${path}.${name}`)
      if (problem) return problem
    }
  }
  if (schema.oneOf) {
    const matching = schema.oneOf.filter((alternative) => validate(value, alternative, path) === null).length
    if (matching !== 1) return `${path} must match exactly one delivery kind`
  }
  return null
}

// ---- HTTP -----------------------------------------------------------------------------------

function send(response, status, body, headers = {}) {
  const text = body === undefined ? '' : typeof body === 'string' ? body : JSON.stringify(body)
  response.writeHead(status, {
    ...(text ? { 'Content-Type': typeof body === 'string' ? 'text/plain' : 'application/json' } : {}),
    ...headers,
  })
  response.end(text)
}

const error = (response, status, code, message) => send(response, status, { code, message })

function readBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    request.on('data', (chunk) => {
      size += chunk.length
      if (size > 1024 * 1024) {
        reject(new Error('too large'))
        request.destroy()
      } else {
        chunks.push(chunk)
      }
    })
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    request.on('error', reject)
  })
}

async function jsonBody(request, response, mediaType = 'application/json') {
  const text = await readBody(request)
  if (text.length === 0) {
    error(response, 400, 'BODY_REQUIRED', 'A request body is required')
    return undefined
  }
  const contentType = String(request.headers['content-type'] ?? '').split(';')[0].trim().toLowerCase()
  if (contentType !== mediaType) {
    error(response, 415, 'UNSUPPORTED_MEDIA_TYPE', `Content-Type must be ${mediaType}`)
    return undefined
  }
  try {
    return { value: JSON.parse(text) }
  } catch {
    error(response, 400, 'MALFORMED_JSON', 'The body is not valid JSON')
    return undefined
  }
}

const products = new Map([[42, { id: 42, name: 'Cordless drill', sku: 'TLS-0042', price: 129.99, category: 'tools', createdAt: new Date().toISOString() }]])

async function route(request, response) {
  const url = new URL(request.url, `http://${HOST}:${PORT}`)
  const path = url.pathname
  const method = request.method

  if (path === '/health' && method === 'GET') return send(response, 200, { status: 'UP' })

  if (path.startsWith('/lab/')) return lab(request, response, url)

  if (request.headers['x-api-key'] !== API_KEY) return error(response, 401, 'UNAUTHORIZED', 'Missing or invalid API key')

  if (path === '/products' && method === 'GET') {
    const requestId = request.headers['x-request-id']
    if (!requestId || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestId)) {
      return error(response, 400, 'BAD_REQUEST', 'X-Request-Id must be a UUID')
    }
    const limit = url.searchParams.get('limit')
    if (limit !== null && (!/^-?\d+$/.test(limit) || Number(limit) < 1 || Number(limit) > 100)) {
      return error(response, 400, 'BAD_REQUEST', 'limit must be an integer between 1 and 100')
    }
    const category = url.searchParams.get('category')
    if (category !== null && !CATEGORY.enum.includes(category)) return error(response, 400, 'BAD_REQUEST', 'unknown category')
    const q = url.searchParams.get('q')
    if (q !== null && ([...q].length < 2 || [...q].length > 50)) return error(response, 400, 'BAD_REQUEST', 'q must have 2-50 characters')
    const items = [...products.values()].slice(0, Number(limit ?? 20))
    return send(response, 200, { items, total: products.size })
  }

  if (path === '/products' && method === 'POST') {
    const body = await jsonBody(request, response)
    if (!body) return undefined
    const problem = validate(body.value, NEW_PRODUCT)
    if (problem) return error(response, 400, 'VALIDATION_FAILED', problem)
    const id = 1000 + products.size
    const product = { ...body.value, id, createdAt: new Date().toISOString() }
    products.set(id, product)
    return send(response, 201, product)
  }

  const productMatch = path.match(/^\/products\/([^/]+)$/)
  if (productMatch) {
    const raw = decodeURIComponent(productMatch[1])
    const id = /^\d{1,9}$/.test(raw) ? Number(raw) : NaN
    if (method === 'PATCH') {
      if (!(id >= 1)) return error(response, 400, 'BAD_REQUEST', 'productId must be a positive integer')
      const body = await jsonBody(request, response, 'application/merge-patch+json')
      if (!body) return undefined
      const problem = validate(body.value, PRODUCT_PATCH)
      if (problem) return error(response, 400, 'VALIDATION_FAILED', problem)
      const current = products.get(id) ?? { id, name: 'Unnamed', sku: 'NEW-0001', price: 1, category: 'tools', createdAt: new Date().toISOString() }
      const updated = { ...current, ...body.value }
      products.set(id, updated)
      return send(response, 200, updated)
    }
    if (!(id >= 1)) return error(response, 404, 'NOT_FOUND', 'No such product')
    if (method === 'GET') {
      const product = products.get(id) ?? { id, name: 'Sample product', sku: 'SMP-0001', price: 9.99, category: 'garden', createdAt: new Date().toISOString() }
      return send(response, 200, product)
    }
    if (method === 'DELETE') {
      products.delete(id)
      return send(response, 204)
    }
  }

  if (path === '/orders' && method === 'POST') {
    const body = await jsonBody(request, response)
    if (!body) return undefined
    const problem = validate(body.value, NEW_ORDER)
    if (problem) return error(response, 400, 'VALIDATION_FAILED', problem)
    return send(response, 201, { ...body.value, id: randomUUID(), status: 'accepted' })
  }

  return error(response, 404, 'NOT_FOUND', 'No such route')
}

async function lab(request, response, url) {
  const path = url.pathname
  if (path === '/lab/echo') {
    return send(response, 200, { method: request.method, headers: request.headers, query: url.search })
  }
  if (path === '/lab/slow') {
    const ms = Math.min(Number(url.searchParams.get('ms') ?? 3000) || 3000, 120000)
    await new Promise((resolve) => setTimeout(resolve, ms))
    return send(response, 200, { waitedMs: ms })
  }
  if (path === '/lab/malformed') {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    return response.end('{"items": [1, 2,')
  }
  const status = path.match(/^\/lab\/status\/(\d{3})$/)
  if (status) return error(response, Number(status[1]), 'STATUS', `Status ${status[1]} on request`)
  if (path === '/lab/redirect') return send(response, 302, undefined, { Location: url.searchParams.get('to') ?? '/health' })
  if (path === '/lab/redirect-metadata') {
    return send(response, 302, undefined, { Location: 'http://169.254.169.254/latest/meta-data/iam/security-credentials/' })
  }
  if (path === '/lab/large') {
    const kb = Math.min(Number(url.searchParams.get('kb') ?? 4096) || 4096, 65536)
    return send(response, 200, 'x'.repeat(kb * 1024))
  }
  return error(response, 404, 'NOT_FOUND', 'No such diagnostic endpoint')
}

createServer((request, response) => {
  route(request, response).catch(() => {
    if (!response.headersSent) error(response, 500, 'INTERNAL', 'Unexpected error')
  })
}).listen(PORT, HOST, () => {
  console.log(`Warehouse Lab API listening on http://${HOST}:${PORT} (X-API-Key: set WAREHOUSE_LAB_API_KEY to change)`)
})
