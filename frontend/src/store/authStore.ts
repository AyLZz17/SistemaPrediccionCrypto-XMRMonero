/**
 * Session state.
 *
 * The access token is held in memory only. `status` drives route protection:
 *   'unknown'      -> bootstrap in flight, render the boot skeleton
 *   'authenticated'-> session usable
 *   'anonymous'    -> no session, protected routes redirect to /login
 */

import { create } from 'zustand'
import type { Role, SessionState, TokenResponse, User } from '../types'
import { clearRefreshToken, readRefreshToken, storeRefreshToken } from '../auth/tokenStorage'

export type AuthStatus = 'unknown' | 'authenticated' | 'anonymous'

export interface AuthStore {
  status: AuthStatus
  accessToken: string | null
  expiresAt: number | null
  refreshToken: string | null
  user: User | null
  /** Set when the backend rejected the refresh token, so the UI can explain it. */
  invalidatedReason: string | null
  setStatus: (status: AuthStatus) => void
  applyTokenResponse: (response: TokenResponse) => void
  /**
   * Session established by the OAuth fragment handoff. That fragment carries only
   * tokens and a role hint, not the full profile, so `user` stays null and is
   * filled in by the subsequent `GET /auth/me`.
   */
  applyOAuthSession: (session: {
    accessToken: string
    refreshToken: string
    expiresIn: number
    role: Role
  }) => void
  setUser: (user: User) => void
  clearSession: (reason?: string | null) => void
  getAccessToken: () => string | null
  getSession: () => SessionState | null
  hasRole: (minimum: Role) => boolean
}

function normaliseRole(role: unknown): Role {
  return role === 'ADMIN' || role === 'ANALYST' || role === 'VIEWER' ? role : 'VIEWER'
}

export const useAuthStore = create<AuthStore>((set, get) => ({
  status: 'unknown',
  accessToken: null,
  expiresAt: null,
  refreshToken: null,
  user: null,
  invalidatedReason: null,

  setStatus: (status) => set({ status }),

  applyTokenResponse: (response) => {
    const refreshToken = response.refreshToken ?? readRefreshToken()
    storeRefreshToken(refreshToken)
    set({
      status: 'authenticated',
      accessToken: response.accessToken,
      expiresAt: Date.now() + Math.max(0, response.expiresIn) * 1000,
      refreshToken,
      user: { ...response.user, role: normaliseRole(response.user?.role) },
      invalidatedReason: null,
    })
  },

  applyOAuthSession: (session) => {
    storeRefreshToken(session.refreshToken)
    set({
      status: 'authenticated',
      accessToken: session.accessToken,
      expiresAt: Date.now() + Math.max(0, session.expiresIn) * 1000,
      refreshToken: session.refreshToken,
      // Deliberately null: the profile is fetched from /auth/me right after.
      user: null,
      invalidatedReason: null,
    })
  },

  setUser: (user) => set({ user: { ...user, role: normaliseRole(user?.role) } }),

  clearSession: (reason = null) => {
    clearRefreshToken()
    set({
      status: 'anonymous',
      accessToken: null,
      expiresAt: null,
      refreshToken: null,
      user: null,
      invalidatedReason: reason,
    })
  },

  getAccessToken: () => get().accessToken,

  getSession: () => {
    const { accessToken, expiresAt, refreshToken, user } = get()
    if (!accessToken || !user) return null
    return { accessToken, expiresAt: expiresAt ?? 0, refreshToken, user }
  },

  hasRole: (minimum) => {
    const user = get().user
    if (!user) return false
    const rank: Record<Role, number> = { VIEWER: 1, ANALYST: 2, ADMIN: 3 }
    return rank[user.role] >= rank[minimum]
  },
}))

/** Non-reactive accessors for the transport layer. */
export const authStore = {
  getAccessToken: (): string | null => useAuthStore.getState().accessToken,
  getStatus: (): AuthStatus => useAuthStore.getState().status,
  getUser: (): User | null => useAuthStore.getState().user,
  clearSession: (reason: string | null = null): void => useAuthStore.getState().clearSession(reason),
  hasRefreshMaterial: (): boolean => {
    const state = useAuthStore.getState()
    if (state.refreshToken) return true
    return readRefreshToken() !== null
  },
}
