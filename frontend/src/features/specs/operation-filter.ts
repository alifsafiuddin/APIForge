import type { OperationSummary } from './api'

/** Text filter over path, summary, operationId and tags. */
export function matchesQuery(operation: OperationSummary, query: string): boolean {
  const needle = query.trim().toLowerCase()
  if (!needle) {
    return true
  }
  return [operation.path, operation.summary ?? '', operation.operationId ?? '', ...operation.tags].some((value) =>
    value.toLowerCase().includes(needle),
  )
}
