/**
 * Authentication API — Spring Boot `/api/v1/auth/*`.
 *
 * No Google client secret ever reaches this module. The Authorization Code +
 * OIDC exchange is entirely backend-driven: the browser is redirected to
 * `/google/authorize`, Google redirects it to `/google/callback`, and the backend
 * validates state + nonce, performs the exchange with the secret, and 302s back
 * to `/auth/callback` with the session in the URL fragment. See GoogleCallbackPage.
 *
 * The refresh token is delivered twice on purpose: in the body (for non-browser
 * clients) and in the HttpOnly `xmr_refresh` cookie. `logout` and `refresh` work
 * with either, so a browser-only client can ignore the body entirely.
 */

import { absoluteUrl, apiRequest } from './client'
import { rawRequest } from '../auth/rawRequest'
import { newRequestId } from './requestId'
import { ApiError } from './errors'
import type {
  ChangePasswordRequest,
  ForgotPasswordRequest,
  LoginRequest,
  RefreshRequest,
  RegisterRequest,
  ResetPasswordRequest,
  TokenResponse,
  User,
} from '../types'

const BASE = '/api/v1/auth'

export async function register(payload: RegisterRequest): Promise<User> {
  const { data } = await apiRequest<User>(`${BASE}/register`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    skipRefresh: true,
  })
  return data
}

export async function login(payload: LoginRequest): Promise<TokenResponse> {
  const { data } = await rawRequest<TokenResponse>(`${BASE}/login`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    requestId: newRequestId(),
  })
  return data
}

/** Explicit refresh (used by the "session expired" action, not by the interceptor). */
export async function refresh(payload: RefreshRequest): Promise<TokenResponse> {
  const { data } = await rawRequest<TokenResponse>(`${BASE}/refresh`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    requestId: newRequestId(),
  })
  return data
}

/** Revokes the refresh token server-side; 204 on success. */
export async function logout(payload: RefreshRequest | Record<string, never> = {}): Promise<void> {
  await rawRequest<null>(`${BASE}/logout`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    requestId: newRequestId(),
  })
}

export async function me(): Promise<User> {
  const { data } = await apiRequest<User>(`${BASE}/me`)
  return data
}

export async function forgotPassword(payload: ForgotPasswordRequest): Promise<void> {
  await rawRequest<null>(`${BASE}/password/forgot`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    requestId: newRequestId(),
  })
}

export async function resetPassword(payload: ResetPasswordRequest): Promise<void> {
  await rawRequest<null>(`${BASE}/password/reset`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    requestId: newRequestId(),
  })
}

export async function changePassword(payload: ChangePasswordRequest): Promise<void> {
  await apiRequest<null>(`${BASE}/password/change`, { method: 'POST', body: payload })
}

/** Where the "Continuar con Google" button sends the browser. */
export function googleAuthorizeUrl(returnTo = '/'): string {
  const target = `${BASE}/google/authorize?returnTo=${encodeURIComponent(returnTo)}`
  return absoluteUrl(target)
}

/**
 * There is deliberately no client-side code exchange: Google redirects the
 * browser to the backend `/google/callback`, which validates state + nonce,
 * performs the OIDC exchange with the client secret, and 302-redirects back to
 * `/auth/callback` with the session in the URL fragment. See GoogleCallbackPage.
 */

export { ApiError }
