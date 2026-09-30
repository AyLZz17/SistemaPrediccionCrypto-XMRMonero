import { vi } from 'vitest'
import { newRequestId } from '../api/requestId'

export interface StubResponseInit {
  status?: number
  json?: unknown
  text?: string
  headers?: Record<string, string>
  /** Never resolves: simulates a request that exceeds the client timeout. */
  hang?: boolean
}

export interface StubCall {
  url: string
  method: string
  headers: Record<string, string>
  body: unknown
}

function makeHeaders(extra: Record<string, string> = {}): Headers {
  return new Headers({ 'content-type': 'application/json', ...extra })
}

export function jsonResponse(init: StubResponseInit = {}): Response {
  const status = init.status ?? 200
  const body = init.text ?? (init.json === undefined ? '' : JSON.stringify(init.json))
  return new Response(body === '' ? null : body, {
    status,
    headers: makeHeaders(init.headers),
  })
}

export function errorResponse(
  status: number,
  body: Record<string, unknown> = {},
  requestId = newRequestId(),
): Response {
  const payload = {
    timestamp: new Date().toISOString(),
    status,
    error: body.error ?? 'Error',
    code: body.code ?? `HTTP_${status}`,
    message: body.message ?? 'Error de prueba',
    path: body.path ?? '/api/v1/test',
    requestId,
    ...(body.fieldErrors ? { fieldErrors: body.fieldErrors } : {}),
  }
  return jsonResponse({ status, json: payload, headers: { 'X-Request-Id': requestId } })
}

/**
 * Installs a `fetch` stub. `routes` maps `METHOD /path` (or just `/path`) to a
 * response factory; unmatched requests reject so an unexpected call is loud.
 */
export function installFetchStub(
  routes: Record<string, (call: StubCall) => Response | Promise<Response> | undefined>,
): { calls: StubCall[]; fetchMock: ReturnType<typeof vi.fn> } {
  const calls: StubCall[] = []

  const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const rawUrl = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
    const url = new URL(rawUrl, 'https://localhost:8443')
    const method = (init?.method ?? 'GET').toUpperCase()
    const headers: Record<string, string> = {}
    if (init?.headers) {
      new Headers(init.headers as HeadersInit).forEach((value, key) => {
        headers[key.toLowerCase()] = value
      })
    }
    let body: unknown = undefined
    if (typeof init?.body === 'string') {
      try {
        body = JSON.parse(init.body) as unknown
      } catch {
        body = init.body
      }
    }

    const call: StubCall = { url: url.pathname + url.search, method, headers, body }
    calls.push(call)

    const key = `${method} ${url.pathname}`
    const factory = routes[key] ?? routes[url.pathname]
    if (!factory) {
      return Promise.reject(new TypeError(`fetch stub: ruta no registrada ${key}`))
    }
    const response = await factory(call)
    if (!response) {
      return Promise.reject(new TypeError(`fetch stub: sin respuesta para ${key}`))
    }
    return response
  })

  vi.stubGlobal('fetch', fetchMock)
  return { calls, fetchMock }
}

/** Rejects with a TypeError, mimicking an offline browser. */
export function installOfflineFetch(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(async () => {
    throw new TypeError('Failed to fetch')
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

/** Never settles, so the client's AbortController timeout wins. */
export function installHangingFetch(): ReturnType<typeof vi.fn> {
  const fetchMock = vi.fn(
    (_input: RequestInfo | URL, init?: RequestInit) =>
      new Promise<Response>((_resolve, reject) => {
        init?.signal?.addEventListener('abort', () => {
          const error = new Error('The operation was aborted.')
          error.name = 'AbortError'
          reject(error)
        })
      }),
  )
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

export interface Deferred<T> {
  promise: Promise<T>
  resolve: (value: T) => void
}

/** A promise whose settlement the test controls, for observing loading states. */
export function deferred<T>(): Deferred<T> {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((res) => {
    resolve = res
  })
  return { promise, resolve }
}
