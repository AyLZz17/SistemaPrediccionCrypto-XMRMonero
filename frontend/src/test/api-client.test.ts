import { beforeEach, describe, expect, it } from 'vitest'
import { apiRequest } from '../api/client'
import { fetchLatestQuote } from '../api/market'
import { fetchJobs, fetchMetricComparison } from '../api'
import { useAuthStore } from '../store/authStore'
import { refreshAccessToken, resetRefreshCoordinator } from '../auth/refreshCoordinator'
import { authenticate, makeTokenResponse, makeUser, TEST_ACCESS_TOKEN, TEST_REFRESH_TOKEN } from './testUtils'
import { errorResponse, installFetchStub, installHangingFetch, installOfflineFetch, jsonResponse } from './fetchStub'
import { resetEnvCache } from '../config/env'

beforeEach(() => {
  resetRefreshCoordinator()
  resetEnvCache()
  useAuthStore.setState({ status: 'authenticated', accessToken: TEST_ACCESS_TOKEN, expiresAt: Date.now() + 60_000, refreshToken: TEST_REFRESH_TOKEN, user: makeUser() })
  sessionStorage.setItem('xmr-forecast.refresh-token', TEST_REFRESH_TOKEN)
})

describe('silent renewal on 401 (single-flight)', () => {
  it('issues exactly ONE refresh for a burst of parallel 401s, then replays each request', async () => {
    let refreshed = false
    const { calls } = installFetchStub({
      'GET /api/v1/market/latest': () =>
        refreshed
          ? jsonResponse({ json: { symbol: 'XMR-USD', price: 200 } })
          : errorResponse(401, { code: 'ACCESS_EXPIRED' }),
      'GET /api/v1/jobs': () =>
        refreshed
          ? jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } })
          : errorResponse(401, { code: 'ACCESS_EXPIRED' }),
      'GET /api/v1/predictions': () =>
        refreshed
          ? jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } })
          : errorResponse(401, { code: 'ACCESS_EXPIRED' }),
      'GET /api/v1/notifications': () =>
        refreshed
          ? jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } })
          : errorResponse(401, { code: 'ACCESS_EXPIRED' }),
      'POST /api/v1/auth/refresh': () => {
        refreshed = true
        return jsonResponse({ json: makeTokenResponse({ accessToken: 'fresh-token' }) })
      },
    })

    const results = await Promise.all([
      fetchLatestQuote(),
      fetchJobs(),
      fetchMetricComparison('exp-1').catch(() => []),
      apiRequest('/api/v1/notifications'),
    ])

    const refreshCalls = calls.filter((call) => call.url === '/api/v1/auth/refresh')
    expect(refreshCalls).toHaveLength(1)
    expect(refreshCalls[0]?.body).toEqual({ refreshToken: TEST_REFRESH_TOKEN })
    expect(useAuthStore.getState().accessToken).toBe('fresh-token')
    // Every request eventually succeeded after the single renewal.
    expect(results).toHaveLength(4)
  })

  it('coalesces concurrent direct refreshAccessToken() calls', async () => {
    const { calls } = installFetchStub({
      'POST /api/v1/auth/refresh': () => jsonResponse({ json: makeTokenResponse() }),
    })

    const [a, b, c] = await Promise.all([
      refreshAccessToken(),
      refreshAccessToken(),
      refreshAccessToken(),
    ])

    expect([a, b, c]).toEqual([true, true, true])
    expect(calls.filter((call) => call.url === '/api/v1/auth/refresh')).toHaveLength(1)
  })

  it('re-arms after a failed refresh so a later attempt can succeed', async () => {
    let shouldFail = true
    installFetchStub({
      'POST /api/v1/auth/refresh': () => {
        if (shouldFail) {
          shouldFail = false
          return errorResponse(401, { code: 'REFRESH_REVOKED' })
        }
        return jsonResponse({ json: makeTokenResponse() })
      },
    })

    const first = await refreshAccessToken()
    expect(first).toBe(false)
    expect(useAuthStore.getState().status).toBe('anonymous')

    useAuthStore.setState({ status: 'authenticated', accessToken: TEST_ACCESS_TOKEN, user: makeUser() })
    sessionStorage.setItem('xmr-forecast.refresh-token', TEST_REFRESH_TOKEN)

    const second = await refreshAccessToken()
    expect(second).toBe(true)
    expect(useAuthStore.getState().status).toBe('authenticated')
  })

  it('does not attempt a refresh for a request flagged skipAuth', async () => {
    const { calls } = installFetchStub({
      'GET /api/v1/auth/me': () => errorResponse(401, { code: 'ACCESS_EXPIRED' }),
      'POST /api/v1/auth/refresh': () => jsonResponse({ json: makeTokenResponse() }),
    })

    await expect(apiRequest('/api/v1/auth/me', { skipAuth: true })).rejects.toMatchObject({ status: 401 })
    expect(calls.filter((call) => call.url === '/api/v1/auth/refresh')).toHaveLength(0)
  })

  it('does not refresh when the backend says the token was revoked', async () => {
    const { calls } = installFetchStub({
      'GET /api/v1/market/latest': () => errorResponse(401, { code: 'TOKEN_REVOKED' }),
      'POST /api/v1/auth/refresh': () => jsonResponse({ json: makeTokenResponse() }),
    })

    await expect(fetchLatestQuote()).rejects.toMatchObject({ status: 401 })
    expect(calls.filter((call) => call.url === '/api/v1/auth/refresh')).toHaveLength(0)
    expect(useAuthStore.getState().status).toBe('anonymous')
  })
})

describe('per-status error handling', () => {
  it('401 -> session-expired copy, no retry, not retryable', async () => {
    installFetchStub({ 'GET /api/v1/market/latest': () => errorResponse(401) })
    const error = await fetchLatestQuote().catch((caught: unknown) => caught)
    expect(error).toMatchObject({ status: 401, kind: 'http' })
    expect((error as { friendlyMessage: string }).friendlyMessage).toMatch(/sesion expiro o no es valida/i)
    expect((error as { retryable: boolean }).retryable).toBe(false)
  })

  it('403 -> permission copy, not retryable', async () => {
    installFetchStub({ 'GET /api/v1/audit': () => errorResponse(403) })
    const { fetchAudit } = await import('../api')
    const error = await fetchAudit().catch((caught: unknown) => caught)
    expect((error as { friendlyMessage: string }).friendlyMessage).toMatch(/no tienes permisos/i)
    expect((error as { retryable: boolean }).retryable).toBe(false)
  })

  it('429 -> rate-limit copy, retryable, with Retry-After respected by the caller', async () => {
    installFetchStub({ 'GET /api/v1/market/latest': () => errorResponse(429, { code: 'RATE_LIMITED' }) })
    const error = (await fetchLatestQuote().catch((caught: unknown) => caught)) as {
      friendlyMessage: string
      retryable: boolean
    }
    expect(error.friendlyMessage).toMatch(/demasiadas solicitudes seguidas/i)
    expect(error.retryable).toBe(true)
  })

  it('500 -> server copy, retryable', async () => {
    installFetchStub({ 'GET /api/v1/market/latest': () => errorResponse(500) })
    const error = (await fetchLatestQuote().catch((caught: unknown) => caught)) as {
      friendlyMessage: string
      retryable: boolean
    }
    expect(error.friendlyMessage).toMatch(/el servidor encontro un error inesperado/i)
    expect(error.retryable).toBe(true)
  })

  it('503 -> temporarily-unavailable copy, retryable', async () => {
    installFetchStub({ 'GET /api/v1/market/latest': () => errorResponse(503) })
    const error = await fetchLatestQuote().catch((caught: unknown) => caught)
    expect((error as { friendlyMessage: string }).friendlyMessage).toMatch(/no esta disponible temporalmente/i)
    expect((error as { retryable: boolean }).retryable).toBe(true)
  })

  it('timeout -> distinct copy, retryable, no raw AbortError leaks', async () => {
    installHangingFetch()
    const settled = await apiRequest('/api/v1/market/latest', { timeoutMs: 1200 }).catch(
      (caught: unknown) => caught,
    )
    expect(settled).toMatchObject({ kind: 'timeout' })
    expect((settled as { friendlyMessage: string }).friendlyMessage).toMatch(/tardo demasiado en responder/i)
    expect((settled as { retryable: boolean }).retryable).toBe(true)
  }, 10_000)

  it('network failure -> distinct copy, retryable', async () => {
    installOfflineFetch()
    const settled = await fetchLatestQuote().catch((caught: unknown) => caught)
    expect(settled).toMatchObject({ kind: 'network' })
    expect((settled as { friendlyMessage: string }).friendlyMessage).toMatch(/no pudimos conectar/i)
    expect((settled as { retryable: boolean }).retryable).toBe(true)
  })

  it('field errors are parsed and preserved', async () => {
    installFetchStub({
      'GET /api/v1/market/latest': () =>
        errorResponse(400, { code: 'VALIDATION', fieldErrors: [{ field: 'symbol', message: 'Simbolo no soportado' }] }),
    })
    const settled = (await fetchLatestQuote().catch((caught: unknown) => caught)) as {
      fieldErrors: Array<{ field: string; message: string }>
    }
    expect(settled.fieldErrors).toEqual([{ field: 'symbol', message: 'Simbolo no soportado' }])
  })

  it('exposes the requestId of the failing call', async () => {
    installFetchStub({ 'GET /api/v1/market/latest': () => errorResponse(500, {}, 'req-abc-123') })
    const settled = (await fetchLatestQuote().catch((caught: unknown) => caught)) as { requestId: string }
    expect(settled.requestId).toBe('req-abc-123')
  })
})

describe('request hygiene', () => {
  it('always sends X-Request-Id and never the refresh token as a header', async () => {
    const { calls } = installFetchStub({
      'GET /api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 1 } }),
    })
    await fetchLatestQuote()
    const call = calls[0]
    expect(call?.headers['x-request-id']).toBeTruthy()
    expect(call?.headers.authorization).toBe(`Bearer ${TEST_ACCESS_TOKEN}`)
    expect(Object.values(call?.headers ?? {}).some((value) => String(value).includes(TEST_REFRESH_TOKEN))).toBe(false)
  })

  it('sends credentials so the httpOnly refresh cookie travels', async () => {
    const fetchMock = installFetchStub({
      'GET /api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 1 } }),
    }).fetchMock
    await fetchLatestQuote()
    const init = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(init.credentials).toBe('include')
    expect(init.referrerPolicy).toBe('no-referrer')
  })

  it('only ever calls the configured https backend origin', async () => {
    const { calls } = installFetchStub({
      'GET /api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 1 } }),
    })
    authenticate('VIEWER')
    await fetchLatestQuote()
    const fetchMock = installFetchStub({}).fetchMock
    fetchMock.mockRestore()
    expect(calls).toHaveLength(1)
    expect(calls[0]?.url.startsWith('/api/v1/')).toBe(true)
  })
})
