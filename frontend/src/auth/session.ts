/**
 * Session lifecycle helpers shared by the router guards and the OAuth callback.
 */

import { hasRefreshMaterial, readRefreshToken } from './tokenStorage'
import { refreshAccessToken } from './refreshCoordinator'
import { useAuthStore, type AuthStatus } from '../store/authStore'

let bootstrapPromise: Promise<AuthStatus> | null = null

/**
 * Restores a session after a full page reload.
 *
 * The access token is memory-only, so a reload always needs a silent refresh.
 * The promise is memoised so several guards mounting at once cannot fire
 * parallel refresh calls (single-flight at the bootstrap level too).
 */
export function bootstrapSession(): Promise<AuthStatus> {
  if (bootstrapPromise) return bootstrapPromise

  bootstrapPromise = (async () => {
    const store = useAuthStore.getState()
    if (store.status === 'authenticated') return 'authenticated'
    if (!hasRefreshMaterial()) {
      store.setStatus('anonymous')
      return 'anonymous'
    }
    const ok = await refreshAccessToken()
    return ok ? 'authenticated' : 'anonymous'
  })().finally(() => {
    bootstrapPromise = null
  })

  return bootstrapPromise
}

/** Test seam. */
export function resetBootstrap(): void {
  bootstrapPromise = null
}

export function hasStoredRefreshToken(): boolean {
  return readRefreshToken() !== null
}
