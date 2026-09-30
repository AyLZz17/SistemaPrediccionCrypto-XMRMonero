/**
 * Single-flight refresh coordination.
 *
 * A burst of parallel 401s (React Query refetching three widgets at once, or a
 * user hammering a button) must produce exactly ONE `POST /auth/refresh`, not
 * one per request. `refreshInFlight` is the shared promise; concurrent callers
 * await the same one and a later call re-arms it once it settles.
 */

import { newRequestId } from '../api/requestId'
import { setRefreshHandler } from '../api/client'
import { rawRequest } from './rawRequest'
import { readRefreshToken } from './tokenStorage'
import { useAuthStore } from '../store/authStore'
import { getEnv } from '../config/env'
import type { TokenResponse } from '../types'

let refreshInFlight: Promise<boolean> | null = null

function performRefresh(): Promise<boolean> {
  const body =
    getEnv().refreshTokenMode === 'body' ? { refreshToken: readRefreshToken() ?? '' } : undefined

  if (getEnv().refreshTokenMode === 'body' && !body?.refreshToken) {
    useAuthStore.getState().clearSession('Tu sesion expiro. Vuelve a iniciar sesion.')
    return Promise.resolve(false)
  }

  return rawRequest<TokenResponse>('/api/v1/auth/refresh', {
    method: 'POST',
    body: body ?? {},
    requestId: newRequestId(),
    // Never recurse into the refresh path.
    skipAuth: true,
  })
    .then(({ data }) => {
      if (!data?.accessToken) {
        useAuthStore.getState().clearSession('Tu sesion expiro. Vuelve a iniciar sesion.')
        return false
      }
      useAuthStore.getState().applyTokenResponse(data)
      return true
    })
    .catch(() => {
      useAuthStore.getState().clearSession('Tu sesion expiro. Vuelve a iniciar sesion.')
      return false
    })
}

/** Returns true when a usable session exists afterwards. Concurrent-safe. */
export function refreshAccessToken(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight
  refreshInFlight = performRefresh().finally(() => {
    refreshInFlight = null
  })
  return refreshInFlight
}

/** Test seam: drops any in-flight promise so suites start from a clean slate. */
export function resetRefreshCoordinator(): void {
  refreshInFlight = null
}

setRefreshHandler(refreshAccessToken)
