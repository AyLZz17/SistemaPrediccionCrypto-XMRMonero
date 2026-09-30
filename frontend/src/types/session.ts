import type { Role } from './auth'

export interface User {
  id: string
  email: string
  fullName: string
  role: Role
  createdAt?: string
  lastLoginAt?: string
  enabled?: boolean
}

export interface TokenResponse {
  accessToken: string
  /** Only present when VITE_REFRESH_TOKEN_MODE=body. */
  refreshToken?: string | null
  tokenType: string
  /** Access token lifetime in seconds. */
  expiresIn: number
  user: User
}

export interface RegisterRequest {
  email: string
  password: string
  fullName: string
}

export interface LoginRequest {
  email: string
  password: string
}

export interface RefreshRequest {
  refreshToken: string
}

export interface ForgotPasswordRequest {
  email: string
}

export interface ResetPasswordRequest {
  token: string
  newPassword: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

/** Shape held in memory / sessionStorage by the auth store. */
export interface SessionState {
  accessToken: string
  expiresAt: number
  refreshToken: string | null
  user: User
}
