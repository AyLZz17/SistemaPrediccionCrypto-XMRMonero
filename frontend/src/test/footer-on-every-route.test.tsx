import { describe, expect, it } from 'vitest'
import { screen } from '@testing-library/react'
import { renderApp, authenticate } from './testUtils'
import { installFetchStub, jsonResponse } from './fetchStub'

/**
 * MANDATORY CONTRACT (frontend/README.md § Footer).
 *
 * The footer text must appear, character for character, on EVERY route and every
 * visible state. The literal is written out here independently of the component
 * constant so the test really checks the characters, not the export.
 */
const EXPECTED_FOOTER =
  'Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.'

/** Minimal happy responses so authenticated pages can render their shell. */
function stubApi() {
  return installFetchStub({
    '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 172.44, changePercent: 1.2 } }),
    '/api/v1/market/candles': () => jsonResponse({ json: { items: [], page: 0, size: 30, total: 0, totalPages: 0 } }),
    '/api/v1/predictions': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
    '/api/v1/jobs': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
    '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
    '/api/v1/datasets': () => jsonResponse({ json: { items: [], page: 0, size: 20, total: 0, totalPages: 0 } }),
    '/api/v1/experiments': () => jsonResponse({ json: { items: [], page: 0, size: 50, total: 0, totalPages: 0 } }),
    '/api/v1/models': () => jsonResponse({ json: { items: [], page: 0, size: 20, total: 0, totalPages: 0 } }),
    '/api/v1/metrics/compare': () => jsonResponse({ json: [] }),
    '/api/v1/audit': () => jsonResponse({ json: { items: [], page: 0, size: 20, total: 0, totalPages: 0 } }),
    '/api/v1/users': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
    '/api/v1/auth/me': () =>
      jsonResponse({ json: { id: 'user-1', email: 'admin@ejemplo.com', fullName: 'Ana', role: 'ADMIN' } }),
    // Panel publico (primera pantalla): se responde con agregados minimos.
    '/api/v1/public/summary': () =>
      jsonResponse({
        json: {
          symbol: 'XMR-USD',
          price: 160.5,
          previousClose: 158,
          change: 2.5,
          changePercent: 1.58,
          high: 162,
          low: 157.1,
          updatedAt: '2026-10-01T06:15:00Z',
          source: 'yahoo-finance',
        },
      }),
    '/api/v1/public/series': () => jsonResponse({ json: [] }),
    '/api/v1/public/models': () => jsonResponse({ json: [] }),
    '/api/v1/public/metrics': () =>
      jsonResponse({ json: { experimentCode: null, available: false, best: null, validation: null, test: null } }),
    '/api/v1/public/comparison': () => jsonResponse({ json: [] }),
    '/api/v1/public/status': () =>
      jsonResponse({
        json: {
          generatedAt: '2026-10-01T06:15:00Z',
          dataUpdatedAt: '2026-10-01T06:15:00Z',
          dataPoints: 1500,
          models: 5,
          champions: 3,
          experimentCode: null,
          experimentStatus: null,
          experimentUpdatedAt: null,
          legalVersion: '2026-10-01',
        },
      }),
  })
}

const PUBLIC_ROUTES = [
  '/',
  '/about',
  '/login',
  '/register',
  '/forgot-password',
  '/reset-password',
  '/auth/callback',
  '/verify-email',
  '/verificar-email',
  '/terms',
  '/privacy',
  '/data-policy',
  '/cookies',
  '/legal-notice',
]

const AUTHENTICATED_ROUTES = [
  '/dashboard',
  '/market',
  '/predictions',
  '/experiments',
  '/metrics',
  '/models',
  '/jobs',
  '/account',
  '/admin',
  '/admin/audit',
  '/admin/logs',
]

describe('footer mandatory text on every route', () => {
  it.each(PUBLIC_ROUTES)('renders the exact footer literal on %s', (route) => {
    stubApi()
    renderApp(route)
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })

  it.each(AUTHENTICATED_ROUTES)('renders the exact footer literal on %s', (route) => {
    stubApi()
    authenticate('ADMIN')
    renderApp(route)
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })

  it('renders the footer on the 404 route', () => {
    stubApi()
    renderApp('/ruta/que/no/existe')
    expect(screen.getByRole('heading', { name: /ruta no encontrada/i })).toBeInTheDocument()
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })

  it('renders the footer on the unauthenticated redirect state (login)', () => {
    stubApi()
    renderApp('/dashboard')
    // ProtectedRoute redirected to /login, which itself carries the footer.
    expect(screen.getByRole('heading', { name: /iniciar sesión/i })).toBeInTheDocument()
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })

  it('renders the footer while the session is being restored (boot skeleton)', async () => {
    stubApi()
    renderApp('/dashboard')
    // status === 'unknown' -> the ProtectedRoute skeleton with its own footer.
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })

  it('renders the footer on the 403 forbidden state', () => {
    stubApi()
    authenticate('VIEWER')
    renderApp('/admin')
    expect(screen.getByText(/no tienes acceso a esta seccion/i)).toBeInTheDocument()
    expect(screen.getAllByText(EXPECTED_FOOTER).length).toBeGreaterThan(0)
  })

  it('exposes the footer as a contentinfo landmark', () => {
    stubApi()
    renderApp('/')
    const footer = screen.getByRole('contentinfo')
    expect(footer).toHaveTextContent(EXPECTED_FOOTER)
  })
})
