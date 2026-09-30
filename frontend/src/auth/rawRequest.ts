/**
 * Auth-only raw transport.
 *
 * Deliberately bypasses `api/client.ts` refresh interception so the refresh
 * endpoint itself can never trigger another refresh (infinite loop). Kept in
 * its own module so the dependency graph stays acyclic:
 *
 *   api/client  ->  (no auth imports, refresh handler injected)
 *   auth/rawRequest -> api/errors, api/requestId, config/env
 *   auth/refreshCoordinator -> auth/rawRequest, store/authStore
 */

import { getEnv } from '../config/env'
import { ApiError, parseErrorBody } from '../api/errors'
import { newRequestId } from '../api/requestId'
import { authStore } from '../store/authStore'

export interface RawRequestInit {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE'
  body?: unknown
  requestId?: string
  skipAuth?: boolean
  timeoutMs?: number
  headers?: Record<string, string>
}

export interface RawResponse<T> {
  data: T
  requestId: string
  status: number
}

function isAbort(error: unknown): boolean {
  return error instanceof Error && (error.name === 'AbortError' || error.name === 'TimeoutError')
}

/** A single fetch with no retry/refresh logic. Throws `ApiError`. */
export async function rawRequest<T>(path: string, init: RawRequestInit = {}): Promise<RawResponse<T>> {
  const { apiBaseUrl, requestTimeoutMs } = getEnv()
  const requestId = init.requestId ?? newRequestId()
  const controller = new AbortController()
  let timedOut = false
  const timer = setTimeout(() => {
    timedOut = true
    controller.abort()
  }, init.timeoutMs ?? requestTimeoutMs)

  const headers: Record<string, string> = {
    Accept: 'application/json',
    'X-Request-Id': requestId,
    ...init.headers,
  }
  if (init.body !== undefined) headers['Content-Type'] = headers['Content-Type'] ?? 'application/json'
  if (!init.skipAuth) {
    const token = authStore.getAccessToken()
    if (token) headers.Authorization = `Bearer ${token}`
  }

  let response: Response
  try {
    response = await fetch(`${apiBaseUrl}${path}`, {
      method: init.method ?? 'GET',
      headers,
      credentials: 'include',
      referrerPolicy: 'no-referrer',
      signal: controller.signal,
      ...(init.body === undefined
        ? {}
        : { body: typeof init.body === 'string' ? init.body : JSON.stringify(init.body) }),
    })
  } catch (error) {
    if (timedOut) throw new ApiError({ kind: 'timeout', path, requestId })
    if (isAbort(error)) throw new ApiError({ kind: 'aborted', path, requestId })
    throw new ApiError({ kind: 'network', path, requestId, message: String(error) })
  } finally {
    clearTimeout(timer)
  }

  const serverRequestId = response.headers.get('X-Request-Id')
  const text = response.status === 204 ? '' : await response.text()
  let data: unknown = null
  if (text.length > 0) {
    try {
      data = JSON.parse(text) as unknown
    } catch {
      data = text
    }
  }

  if (!response.ok) {
    const body = parseErrorBody(data)
    throw new ApiError({
      kind: 'http',
      status: response.status,
      code: body.code ?? null,
      message: body.message,
      path: body.path ?? path,
      requestId: body.requestId ?? serverRequestId ?? requestId,
      fieldErrors: body.fieldErrors,
    })
  }

  return {
    data: data as T,
    requestId: serverRequestId ?? requestId,
    status: response.status,
  }
}
