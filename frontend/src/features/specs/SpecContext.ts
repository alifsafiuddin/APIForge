import { useOutletContext } from 'react-router'

import type { VersionSummary } from './api'

/** Shared by the specification page with its overview and operation-detail panels. */
export type SpecOutletContext = {
  projectId: string
  version: VersionSummary
  /** "?version=<id>" when a non-active version is being viewed, otherwise "". */
  versionQuery: string
}

export function useSpecContext() {
  return useOutletContext<SpecOutletContext>()
}
