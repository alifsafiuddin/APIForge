import type { Check, CheckResult } from './api'

/** "5 checks: 3 passed, 1 failed, 1 skipped". Skipped checks are never added to the passes. */
export function checkSummary(checks: Check[]): string {
  const count = (...results: CheckResult[]) => checks.filter((check) => results.includes(check.result)).length
  const parts = [
    [count('PASS'), 'passed'],
    [count('FAIL'), 'failed'],
    [count('INCONCLUSIVE'), 'inconclusive'],
    [count('SKIPPED', 'NOT_EVALUATED'), 'skipped (not validated)'],
  ] as const
  return `${checks.length} check${checks.length === 1 ? '' : 's'}: ${parts
    .filter(([n]) => n > 0)
    .map(([n, label]) => `${n} ${label}`)
    .join(', ')}`
}
