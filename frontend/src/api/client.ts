/**
 * HTTP transport for the Spring Boot backend.
 *
 * Guarantees:
 *  - every request is sent to `VITE_API_BASE_URL` (https:// only, validated);
 *  - the frontend NEVER talks to PostgreSQL, Redis, MLflow or the ML service
 *    (R-32) — this is the single egress point, and the ML service is not a
 *    configured origin;
 *  - `X-Request-Id` is attached to every request and read back from the
 *    response for correlation with backend/ML logs;
 *  - `credentials: 'include'` so the httpOnly refresh cookie travels with
 *    same-origin / CORS-credentialed calls;
 *  - hard per-request timeout, and a single-flight 401 refresh with exactly one
 *    replay of the original request (no refresh storm);
 *  - every failure leaves as an `ApiError` with a friendly per-status message.
 */

import { buildApiUrl, getEnv } from '../config/env'
import { authStore } from '../store/authStore'
import { ApiError, parseErrorBody } from './errors'
import { newRequestId, resolveRequestId } from './requestId'

export type HttpMethod = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'

export interface ApiRequestInit {
  method?: HttpMethod
  body?: unknown
  query?: Record<string, string | number | boolean | undefined | null>
  signal?: AbortSignal
  /** Set for auth endpoints so a 401 there never triggers a refresh loop. */
  skipAuth?: boolean
  /** Internal: prevents infinite replay. */
  skipRefresh?: boolean
  headers?: Record<string, string>
  timeoutMs?: number
}

export interface ApiResponse<T> {
  data: T
  requestId: string
  status: number
}

function buildQuery(query: ApiRequestInit['query']): string {
  if (!query) return ''
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    params.append(key, String(value))
  }
  const qs = params.toString()
  return qs ? `?${qs}` : ''
}

/** Awaits a refresh attempt. Installed by `auth/refreshCoordinator` to avoid an import cycle. */
export type RefreshHandler = () => Promise<boolean>

let refreshHandler: RefreshHandler | null = null

export function setRefreshHandler(handler: RefreshHandler | null): void {
  refreshHandler = handler
}

export function getRequestId(response: Response, fallback: string): string {
  return resolveRequestId(response.headers.get('X-Request-Id'), fallback)
}

async function parseBody(response: Response): Promise<unknown> {
  if (response.status === 204 || response.status === 205) return null
  const contentType = response.headers.get('Content-Type') ?? ''
  const text = await response.text()
  if (text.length === 0) return null
  if (contentType.includes('application/json') || /^[[{"]/.test(text.trim())) {
    try {
      return JSON.parse(text) as unknown
    } catch {
      return text
    }
  }
  return text
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && (error.name === 'AbortError' || error.name === 'TimeoutError')
}

async function execute<T>(url: string, init: ApiRequestInit, requestId: string): Promise<ApiResponse<T>> {
  const { apiBaseUrl, requestTimeoutMs } = getEnv()
  const controller = new AbortController()
  const timeoutMs = init.timeoutMs ?? requestTimeoutMs

  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, timeoutMs)

  const onExternalAbort = () => controller.abort()
  init.signal?.addEventListener('abort', onExternalAbort)

  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Request-Id': requestId,
    ...init.headers,
  }

  if (init.body !== undefined && init.body !== null) {
    headers['Content-Type'] = headers['Content-Type'] ?? 'application/json'
  }

  if (!init.skipAuth) {
    const token = authStore.getAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${url}${buildQuery(init.query)}`, {
      method: init.method ?? 'GET',
      headers,
      // Required for the httpOnly refresh cookie (R-27/R-33).
      credentials: 'include',
      // R-33: never silently downgrade a request to plaintext.
      referrerPolicy: 'no-referrer',
      signal: controller.signal,
      ...(init.body === undefined || init.body === null
        ? {}
        : { body: typeof init.body === 'string' ? init.body : JSON.stringify(init.body) }),
    })
  } catch (error) {
    if (timedOut) {
      throw new ApiError({ kind: 'timeout', path: url, requestId })
    }
    if (isAbortError(error) || init.signal?.aborted) {
      throw new ApiError({ kind: 'aborted', path: url, requestId })
    }
    throw new ApiError({ kind: 'network', path: url, requestId, message: String(error) })
  } finally {
    clearTimeout(timer)
    init.signal?.removeEventListener('abort', onExternalAbort)
  }

  const responseRequestId = getRequestId(response, requestId)
  const data = await parseBody(response)

  if (!response.ok) {
    const body = parseErrorBody(data)
    throw new ApiError({
      kind: 'http',
      status: response.status,
      code: body.code ?? null,
      // The server's own message is only used for non-sensitive context; the
      // friendly copy always wins so we never leak internals to the UI.
      message: body.message,
      path: body.path ?? url,
      requestId: body.requestId ?? responseRequestId,
      fieldErrors: body.fieldErrors,
    })
  }

  return { data: data as T, requestId: responseRequestId, status: response.status }
}

/**
 * Performs an API call. On 401 it triggers a single-flight refresh and replays
 * the request once. Any other error is surfaced untouched.
 */
export async function apiRequest<T>(path: string, init: ApiRequestInit = {}): Promise<ApiResponse<T>> {
  const requestId = newRequestId()
  try {
    return await execute<T>(path, init, requestId)
  } catch (error) {
    if (
      error instanceof ApiError &&
      error.status === 401 &&
      !init.skipRefresh &&
      !init.skipAuth
    ) {
      // A rejected session (expired, revoked, tampered) can never be replayed:
      // drop it so the router sends the user back to /login.
      if (error.code === 'TOKEN_REVOKED' || error.code === 'TOKEN_EXPIRED') {
        authStore.clearSession()
        throw error
      }
      const handler = refreshHandler
      if (handler) {
        const refreshed = await handler()
        if (refreshed) {
          return execute<T>(path, { ...init, skipRefresh: true }, newRequestId())
        }
      }
    }
    throw error
  }
}

/** Absolute URL for browser navigation (e.g. the Google authorize redirect). */
export function absoluteUrl(path: string): string {
  return buildApiUrl(path)
}
