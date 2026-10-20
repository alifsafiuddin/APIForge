/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Optional absolute backend URL for cross-origin calls. Empty = same origin via the dev proxy. */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
