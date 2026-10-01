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
  ResendVerificationRequest,
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

export async function verifyEmail(token: string): Promise<void> {
  await rawRequest<null>(`${BASE}/verify-email?token=${encodeURIComponent(token)}`, {
    method: 'POST',
    skipAuth: true,
    requestId: newRequestId(),
  })
}

/**
 * Re-sends the verification mail. The backend answers 204 whether or not the
 * account exists (anti-enumeration), so a silent success is the normal result.
 */
export async function resendVerification(payload: ResendVerificationRequest): Promise<void> {
  await rawRequest<null>(`${BASE}/verify-email/resend`, {
    method: 'POST',
    body: payload,
    skipAuth: true,
    requestId: newRequestId(),
  })
}

export async function changePassword(payload: ChangePasswordRequest): Promise<void> {
  await apiRequest<null>(`${BASE}/password/change`, { method: 'POST', body: payload })
}

/** Consent checkboxes ticked before leaving for Google (new accounts only). */
export interface GoogleConsentParams {
  acceptTerms?: boolean
  acceptDataPolicy?: boolean
  acceptMarketing?: boolean
}

/** Where the "Continuar con Google" button sends the browser. */
export function googleAuthorizeUrl(returnTo = '/', consent?: GoogleConsentParams): string {
  const params = new URLSearchParams({ returnTo })
  // Only the affirmative values are sent: an absent parameter means "not
  // accepted", which is exactly what the backend assumes.
  if (consent?.acceptTerms) params.set('acceptTerms', 'true')
  if (consent?.acceptDataPolicy) params.set('acceptDataPolicy', 'true')
  if (consent?.acceptMarketing) params.set('acceptMarketing', 'true')
  const target = `${BASE}/google/authorize?${params.toString()}`
  return absoluteUrl(target)
}

/**
 * There is deliberately no client-side code exchange: Google redirects the
 * browser to the backend `/google/callback`, which validates state + nonce,
 * performs the OIDC exchange with the client secret, and 302-redirects back to
 * `/auth/callback` with the session in the URL fragment. See GoogleCallbackPage.
 */

export { ApiError }
