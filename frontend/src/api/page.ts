import { z } from 'zod'

/** Mirrors the backend PageResponse: {"content": [...], "page": {...}}. */
export function pageSchema<T extends z.ZodType>(item: T) {
  return z.object({
    content: z.array(item),
    page: z.object({
      number: z.number(),
      size: z.number(),
      totalElements: z.number(),
      totalPages: z.number(),
    }),
  })
}

export type Page<T> = {
  content: T[]
  page: { number: number; size: number; totalElements: number; totalPages: number }
}
