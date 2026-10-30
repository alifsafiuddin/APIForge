/**
 * "42%" for value of total, never rounded up to 100% unless everything is covered and never
 * down to 0% when something is; "–" when there is nothing to measure.
 */
export function percent(value: number, total: number | null): string {
  if (total === null || total === 0) return '–'
  if (value >= total) return '100%'
  const share = (value / total) * 100
  if (share > 0 && share < 1) return '<1%'
  return `${Math.min(99, Math.round(share))}%`
}

/** Labels of the summary numbers stored in snapshots (CoverageCalculator keys). */
export const SUMMARY_LABELS: [string, string][] = [
  ['operations', 'Operations'],
  ['operationsWithTests', 'Operations with tests'],
  ['tests', 'Enabled tests (distinct)'],
  ['duplicates', 'Duplicates not counted'],
  ['responses', 'Documented responses'],
  ['responsesTargeted', 'Responses targeted'],
  ['responsesExercised', 'Responses exercised'],
  ['constraints', 'Known request constraints'],
  ['constraintsCovered', 'Constraints covered'],
  ['branches', 'Schema branches'],
  ['branchesExercised', 'Branches exercised'],
  ['negativeKinds', 'Applicable negative kinds'],
  ['negativeKindsPresent', 'Negative kinds present'],
  ['executed', 'Tests executed'],
  ['passed', 'Passed'],
  ['failed', 'Failed'],
  ['error', 'Errors'],
  ['inconclusive', 'Inconclusive'],
  ['skipped', 'Skipped'],
  ['notRun', 'Not run'],
]
