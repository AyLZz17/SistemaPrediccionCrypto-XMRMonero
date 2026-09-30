/**
 * Refresh-token persistence.
 *
 * The access token NEVER touches persistent storage: it lives only in memory
 * (see `authStore`), which makes it unreachable from disk.
 *
 * The refresh token follows `VITE_REFRESH_TOKEN_MODE`:
 *  - `cookie`: the backend sets an httpOnly + Secure + SameSite cookie. This
 *    module stores nothing; the browser attaches the cookie to /auth/refresh.
 *  - `body`  : the backend returns the token in the TokenResponse. We keep it in
 *    `sessionStorage` (tab-scoped, cleared when the tab closes) instead of
 *    `localStorage` to limit the XSS blast radius. R-14: still no secret is
 *    hard-coded in the bundle; this value originates at runtime.
 */

import { getEnv } from '../config/env'

const STORAGE_KEY = 'xmr-forecast.refresh-token'

function safeSessionStorage(): Storage | null {
  try {
    if (typeof globalThis.sessionStorage === 'undefined') return null
    return globalThis.sessionStorage
  } catch {
    // Access can throw in hardened/privacy browser modes.
    return null
  }
}

export function storeRefreshToken(token: string | null | undefined): void {
  if (getEnv().refreshTokenMode === 'cookie') return
  const storage = safeSessionStorage()
  if (!storage) return
  try {
    if (token) storage.setItem(STORAGE_KEY, token)
    else storage.removeItem(STORAGE_KEY)
  } catch {
    /* quota or privacy mode: refresh then falls back to a full re-login. */
  }
}

export function readRefreshToken(): string | null {
  if (getEnv().refreshTokenMode === 'cookie') return null
  const storage = safeSessionStorage()
  if (!storage) return null
  try {
    return storage.getItem(STORAGE_KEY)
  } catch {
    return null
  }
}

export function clearRefreshToken(): void {
  const storage = safeSessionStorage()
  if (!storage) return
  try {
    storage.removeItem(STORAGE_KEY)
  } catch {
    /* no-op */
  }
}

/** True when a silent re-authentication is theoretically possible. */
export function hasRefreshMaterial(): boolean {
  return getEnv().refreshTokenMode === 'cookie' || readRefreshToken() !== null
}
