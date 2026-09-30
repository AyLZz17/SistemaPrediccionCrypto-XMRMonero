/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** Absolute https:// origin of the Spring Boot backend. */
  readonly VITE_API_BASE_URL: string
  /** `cookie` when the backend sets an httpOnly refresh cookie, otherwise `body`. */
  readonly VITE_REFRESH_TOKEN_MODE?: 'cookie' | 'body'
  readonly VITE_REQUEST_TIMEOUT_MS?: string
  /** Dev-server TLS certificate paths (never bundled). */
  readonly VITE_DEV_HTTPS_CERT?: string
  readonly VITE_DEV_HTTPS_KEY?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
