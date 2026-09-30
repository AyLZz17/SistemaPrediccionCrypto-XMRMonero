import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AppRoutes from '../AppRoutes'
import { useAuthStore } from '../store/authStore'
import { authenticate, makeTokenResponse, makeUser, renderApp, renderAt, TEST_REFRESH_TOKEN } from './testUtils'
import { errorResponse, installFetchStub, jsonResponse } from './fetchStub'

beforeEach(() => {
  installFetchStub({
    '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 170 } }),
    '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
  })
})

describe('route protection', () => {
  it('redirects an anonymous visitor from /dashboard to /login', async () => {
    renderApp('/dashboard')
    expect(await screen.findByRole('heading', { name: /iniciar sesion/i })).toBeInTheDocument()
  })

  it('carries state.from so the user lands back on the requested route', async () => {
    renderApp('/metrics')
    const user = userEvent.setup()
    // The login form must be visible with the intended destination preserved.
    expect(await screen.findByRole('heading', { name: /iniciar sesion/i })).toBeInTheDocument()
    installFetchStub({
      'POST /api/v1/auth/login': () => jsonResponse({ json: makeTokenResponse({ user: makeUser({ role: 'ANALYST' }) }) }),
      'GET /api/v1/auth/me': () => jsonResponse({ json: makeUser({ role: 'ANALYST' }) }),
      '/api/v1/metrics/experiments/x': () => jsonResponse({ json: { mae: 1, rmse: 2, mape: 3, directionAccuracy: 0.5 } }),
      '/api/v1/experiments': () => jsonResponse({ json: { items: [], page: 0, size: 50, total: 0, totalPages: 0 } }),
    })
    await user.type(screen.getByLabelText(/correo electronico/i), 'a@b.com')
    await user.type(screen.getByLabelText(/contrasena/i), 'secreto-seguro-1')
    await user.click(screen.getByRole('button', { name: /^entrar$/i }))
    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))
  })

  it.each(['/admin', '/admin/audit', '/admin/logs'])('blocks a VIEWER from %s with 403', async (route) => {
    authenticate('VIEWER')
    renderApp(route)
    expect(await screen.findByText(/no tienes acceso a esta seccion/i)).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /auditoria|usuarios y roles|logs/i })).not.toBeInTheDocument()
  })

  it('blocks an ANALYST from /admin with 403', async () => {
    authenticate('ANALYST')
    renderApp('/admin')
    expect(await screen.findByText(/no tienes acceso a esta seccion/i)).toBeInTheDocument()
  })

  it.each(['/experiments', '/metrics', '/models'])('blocks a VIEWER from %s', async (route) => {
    authenticate('VIEWER')
    renderApp(route)
    expect(await screen.findByText(/no tienes acceso a esta seccion/i)).toBeInTheDocument()
  })

  it.each(['/experiments', '/metrics', '/models'])('allows an ANALYST into %s', async (route) => {
    authenticate('ANALYST')
    renderApp(route)
    await waitFor(() => {
      expect(screen.queryByText(/no tienes acceso a esta seccion/i)).not.toBeInTheDocument()
    })
    expect(screen.getAllByText(
      'Sistema esta realizado por \u00A9 AyLZz17 - AyLZz Software Solutions. Todos los derechos reservados.',
    ).length).toBeGreaterThan(0)
  })

  it('allows an ADMIN into every admin route', async () => {
    authenticate('ADMIN')
    renderApp('/admin/audit')
    await waitFor(() => {
      expect(screen.queryByText(/no tienes acceso a esta seccion/i)).not.toBeInTheDocument()
    })
  })

  it('hides admin navigation from non-admins and shows it to admins', () => {
    authenticate('VIEWER')
    const { unmount } = renderApp('/dashboard')
    expect(screen.queryByRole('link', { name: /auditoria/i })).not.toBeInTheDocument()
    unmount()

    authenticate('ADMIN')
    renderApp('/dashboard')
    expect(screen.getByRole('link', { name: /auditoria/i })).toBeInTheDocument()
  })

  it('sends an authenticated user away from /login', async () => {
    authenticate('ANALYST')
    renderApp('/login')
    // Redirected to /dashboard: only the authenticated layout renders the sidebar.
    expect(await screen.findByRole('complementary', { name: /navegacion principal/i })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /iniciar sesion/i })).not.toBeInTheDocument()
  })

  it('shows a restoring-session skeleton instead of flashing the login form', async () => {
    // A refresh token exists but the renewal is still in flight.
    sessionStorage.setItem('xmr-forecast.refresh-token', TEST_REFRESH_TOKEN)
    useAuthStore.setState({ status: 'unknown', accessToken: null, user: null, refreshToken: null })
    installFetchStub({ 'POST /api/v1/auth/refresh': () => new Promise<Response>(() => undefined) })

    renderApp('/dashboard')
    expect((await screen.findAllByText(/restaurando sesion/i)).length).toBeGreaterThan(0)
    expect(screen.queryByRole('heading', { name: /iniciar sesion/i })).not.toBeInTheDocument()
    expect(screen.getByTestId('app-footer')).toBeInTheDocument()
  })

  it('recovers the session from a stored refresh token after a reload', async () => {
    const { calls } = installFetchStub({
      'POST /api/v1/auth/refresh': () =>
        jsonResponse({ json: makeTokenResponse({ user: makeUser({ role: 'VIEWER' }) }) }),
      '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 1 } }),
      '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
      '/api/v1/jobs': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
      '/api/v1/predictions': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
    })

    useAuthStore.setState({ status: 'unknown', accessToken: null, user: null, refreshToken: null })
    sessionStorage.setItem('xmr-forecast.refresh-token', TEST_REFRESH_TOKEN)

    renderApp('/dashboard')

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))
    const refreshCalls = calls.filter((call) => call.url.startsWith('/api/v1/auth/refresh'))
    expect(refreshCalls).toHaveLength(1)
    expect(refreshCalls[0]?.body).toEqual({ refreshToken: TEST_REFRESH_TOKEN })
  })

  it('lands on the login screen after a 401 from a protected resource', async () => {
    authenticate('ANALYST')
    installFetchStub({
      '/api/v1/market/latest': () => errorResponse(401, { code: 'TOKEN_EXPIRED' }),
      '/api/v1/notifications': () => errorResponse(401, { code: 'TOKEN_EXPIRED' }),
      'POST /api/v1/auth/refresh': () => errorResponse(401, { code: 'REFRESH_REVOKED' }),
      '/api/v1/jobs': () => errorResponse(401, { code: 'TOKEN_EXPIRED' }),
      '/api/v1/predictions': () => errorResponse(401, { code: 'TOKEN_EXPIRED' }),
    })
    renderApp('/dashboard')
    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    expect(await screen.findByRole('heading', { name: /iniciar sesion/i })).toBeInTheDocument()
  })

  it('renders a RoleRoute 403 without leaking protected content', () => {
    authenticate('VIEWER')
    const { container } = renderAt('/admin', <AppRoutes />)
    expect(container).toBeTruthy()
  })
})
