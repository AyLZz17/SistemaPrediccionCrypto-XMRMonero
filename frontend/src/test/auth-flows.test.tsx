import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useAuthStore } from '../store/authStore'
import { readRefreshToken } from '../auth/tokenStorage'
import { authenticate, makeTokenResponse, makeUser, renderApp, TEST_REFRESH_TOKEN } from './testUtils'
import { errorResponse, installFetchStub, jsonResponse } from './fetchStub'

beforeEach(() => {
  installFetchStub({
    '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 170 } }),
    '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
    '/api/v1/jobs': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
    '/api/v1/predictions': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
  })
})

describe('login flow', () => {
  it('stores the session and lands on the dashboard', async () => {
    const { calls } = installFetchStub({
      'POST /api/v1/auth/login': () =>
        jsonResponse({
          json: makeTokenResponse({ user: makeUser({ fullName: 'Ana Lector', role: 'ANALYST' }) }),
        }),
      'GET /api/v1/auth/me': () => jsonResponse({ json: makeUser({ role: 'ANALYST' }) }),
      '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 172.4 } }),
      '/api/v1/notifications': () => jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
      '/api/v1/jobs': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
      '/api/v1/predictions': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
    })

    renderApp('/login')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/correo electronico/i), 'ana@ejemplo.com')
    await user.type(screen.getByLabelText(/contrasena/i), 'contrasena-larga-1')
    await user.click(screen.getByRole('button', { name: /^entrar$/i }))

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))
    expect(await screen.findByText(/centro de operaciones/i)).toBeInTheDocument()
    expect(useAuthStore.getState().user?.role).toBe('ANALYST')
    expect(readRefreshToken()).toBe(TEST_REFRESH_TOKEN)

    const loginCall = calls.find((call) => call.url === '/api/v1/auth/login')
    expect(loginCall?.method).toBe('POST')
    expect(loginCall?.body).toEqual({ email: 'ana@ejemplo.com', password: 'contrasena-larga-1' })
    // Correlation header is always present.
    expect(loginCall?.headers['x-request-id']).toBeTruthy()
  })

  it('shows the 401 message and stays on the login page', async () => {
    installFetchStub({
      'POST /api/v1/auth/login': () => errorResponse(401, { code: 'BAD_CREDENTIALS', message: 'Credenciales invalidas' }),
    })

    renderApp('/login')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/correo electronico/i), 'ana@ejemplo.com')
    await user.type(screen.getByLabelText(/contrasena/i), 'incorrecta')
    await user.click(screen.getByRole('button', { name: /^entrar$/i }))

    expect(await screen.findByText(/tu sesion expiro o no es valida/i)).toBeInTheDocument()
    expect(useAuthStore.getState().status).not.toBe('authenticated')
  })

  it('never leaks the raw server message for a 500', async () => {
    installFetchStub({
      'POST /api/v1/auth/login': () =>
        errorResponse(500, { code: 'INTERNAL', message: 'java.lang.NullPointerException at Service' }),
    })
    renderApp('/login')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/correo electronico/i), 'a@b.com')
    await user.type(screen.getByLabelText(/contrasena/i), 'x')
    await user.click(screen.getByRole('button', { name: /^entrar$/i }))

    expect(await screen.findByText(/el servidor encontro un error inesperado/i)).toBeInTheDocument()
    expect(screen.queryByText(/NullPointerException/)).not.toBeInTheDocument()
  })
})

describe('register flow', () => {
  it('creates the account and redirects to login', async () => {
    const { calls } = installFetchStub({
      'POST /api/v1/auth/register': () =>
        jsonResponse({ status: 201, json: { id: 'u-9', email: 'nuevo@ejemplo.com', fullName: 'Nuevo', role: 'VIEWER' } }),
    })

    renderApp('/register')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/nombre completo/i), 'Nuevo Usuario')
    await user.type(screen.getByLabelText(/correo electronico/i), 'nuevo@ejemplo.com')
    await user.type(screen.getByLabelText(/^contrasena/i), 'contrasena-larga-1')
    await user.type(screen.getByLabelText(/repetir contrasena/i), 'contrasena-larga-1')
    await user.click(screen.getByLabelText(/he leido y acepto los terminos y condiciones/i))
    await user.click(
      screen.getByLabelText(/acepto la politica de tratamiento de datos personales/i),
    )
    await user.click(screen.getByRole('button', { name: /crear cuenta/i }))

    expect(await screen.findByRole('heading', { name: /iniciar sesion/i })).toBeInTheDocument()
    const registerCall = calls.find((call) => call.url === '/api/v1/auth/register')
    expect(registerCall?.method).toBe('POST')
    expect(registerCall?.body).toEqual({
      email: 'nuevo@ejemplo.com',
      password: 'contrasena-larga-1',
      fullName: 'Nuevo Usuario',
      acceptTerms: true,
      acceptDataPolicy: true,
      acceptMarketing: false,
    })
  })

  it('validates locally before calling the API', async () => {
    const { calls } = installFetchStub({})
    renderApp('/register')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/nombre completo/i), 'A')
    await user.type(screen.getByLabelText(/correo electronico/i), 'no-es-correo')
    await user.type(screen.getByLabelText(/^contrasena/i), 'corta')
    await user.type(screen.getByLabelText(/repetir contrasena/i), 'otra')
    await user.click(screen.getByRole('button', { name: /crear cuenta/i }))

    expect(await screen.findByText(/indica tu nombre completo/i)).toBeInTheDocument()
    expect(screen.getByText(/introduce un correo valido/i)).toBeInTheDocument()
    expect(screen.getByText(/la contrasena debe tener al menos 12 caracteres/i)).toBeInTheDocument()
    expect(screen.getByText(/las contrasenas no coinciden/i)).toBeInTheDocument()
    expect(calls.filter((call) => call.url === '/api/v1/auth/register')).toHaveLength(0)
  })

  it('maps server field errors back onto the form', async () => {
    installFetchStub({
      'POST /api/v1/auth/register': () =>
        errorResponse(400, {
          code: 'VALIDATION',
          fieldErrors: [{ field: 'email', message: 'Ya existe una cuenta con ese correo.' }],
        }),
    })
    renderApp('/register')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/nombre completo/i), 'Nuevo Usuario')
    await user.type(screen.getByLabelText(/correo electronico/i), 'duplicado@ejemplo.com')
    await user.type(screen.getByLabelText(/^contrasena/i), 'contrasena-larga-1')
    await user.type(screen.getByLabelText(/repetir contrasena/i), 'contrasena-larga-1')
    await user.click(screen.getByLabelText(/he leido y acepto los terminos y condiciones/i))
    await user.click(
      screen.getByLabelText(/acepto la politica de tratamiento de datos personales/i),
    )
    await user.click(screen.getByRole('button', { name: /crear cuenta/i }))

    // Shown twice on purpose: on the field and in the summary alert.
    expect(screen.getAllByText(/ya existe una cuenta con ese correo/i).length).toBeGreaterThan(0)
  })

  it('never submits without the two mandatory consents', async () => {
    const { calls } = installFetchStub({
      'POST /api/v1/auth/register': () =>
        jsonResponse({
          status: 201,
          json: { id: 'u-10', email: 'nuevo@ejemplo.com', fullName: 'Nuevo Usuario', role: 'VIEWER' },
        }),
    })
    renderApp('/register')
    const user = userEvent.setup()

    await user.type(screen.getByLabelText(/nombre completo/i), 'Nuevo Usuario')
    await user.type(screen.getByLabelText(/correo electronico/i), 'nuevo@ejemplo.com')
    await user.type(screen.getByLabelText(/^contrasena/i), 'contrasena-larga-1')
    await user.type(screen.getByLabelText(/repetir contrasena/i), 'contrasena-larga-1')
    await user.click(screen.getByRole('button', { name: /crear cuenta/i }))

    expect(await screen.findByTestId('consent-error')).toHaveTextContent(
      /debes aceptar los terminos y condiciones/i,
    )
    expect(calls.filter((call) => call.url === '/api/v1/auth/register')).toHaveLength(0)

    // Accepting only the terms is still not enough.
    await user.click(screen.getByLabelText(/he leido y acepto los terminos y condiciones/i))
    await user.click(screen.getByRole('button', { name: /crear cuenta/i }))
    expect(await screen.findByTestId('consent-error')).toHaveTextContent(
      /debes aceptar la politica de tratamiento de datos personales/i,
    )
    expect(calls.filter((call) => call.url === '/api/v1/auth/register')).toHaveLength(0)

    // The marketing box is optional: not ticking it must not block anything.
    await user.click(
      screen.getByLabelText(/acepto la politica de tratamiento de datos personales/i),
    )
    await user.click(screen.getByRole('button', { name: /crear cuenta/i }))
    expect(await screen.findByRole('heading', { name: /iniciar sesion/i })).toBeInTheDocument()
    expect(calls.filter((call) => call.url === '/api/v1/auth/register')).toHaveLength(1)
  })
})

describe('logout', () => {
  it('revokes the refresh token server-side and clears the session', async () => {
    const { calls } = installFetchStub({
      'POST /api/v1/auth/logout': () => jsonResponse({ status: 204 }),
    })
    authenticate('ANALYST')
    renderApp('/account')

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /cerrar sesion y revocar token/i }))

    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    const logoutCall = calls.find((call) => call.url === '/api/v1/auth/logout')
    expect(logoutCall?.method).toBe('POST')
    expect(logoutCall?.body).toEqual({ refreshToken: TEST_REFRESH_TOKEN })
    expect(readRefreshToken()).toBeNull()
  })

  it('clears the session even if the revocation call fails', async () => {
    installFetchStub({
      'POST /api/v1/auth/logout': () => errorResponse(500, { code: 'INTERNAL' }),
    })
    authenticate('ANALYST')
    renderApp('/account')

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /cerrar sesion y revocar token/i }))

    await waitFor(() => expect(useAuthStore.getState().status).toBe('anonymous'))
    expect(readRefreshToken()).toBeNull()
  })

  it('logs out from the header', async () => {
    installFetchStub({ 'POST /api/v1/auth/logout': () => jsonResponse({ status: 204 }) })
    authenticate('VIEWER')
    renderApp('/dashboard')

    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /^salir$/i }))
    expect(useAuthStore.getState().status).toBe('anonymous')
  })
})

describe('password recovery', () => {
  it('requests a recovery email', async () => {
    const { calls } = installFetchStub({ 'POST /api/v1/auth/password/forgot': () => jsonResponse({ status: 204 }) })
    renderApp('/forgot-password')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/correo electronico/i), 'ana@ejemplo.com')
    await user.click(screen.getByRole('button', { name: /enviar enlace/i }))

    expect(await screen.findByText(/si la cuenta existe/i)).toBeInTheDocument()
    expect(calls.find((call) => call.url === '/api/v1/auth/password/forgot')?.body).toEqual({
      email: 'ana@ejemplo.com',
    })
  })

  it('resets the password with a token and returns to login', async () => {
    const { calls } = installFetchStub({ 'POST /api/v1/auth/password/reset': () => jsonResponse({ status: 204 }) })
    renderApp('/reset-password?token=abc123')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/^nueva contrasena/i), 'contrasena-larga-2')
    await user.type(screen.getByLabelText(/repetir nueva contrasena/i), 'contrasena-larga-2')
    await user.click(screen.getByRole('button', { name: /guardar nueva contrasena/i }))

    expect(await screen.findByRole('heading', { name: /iniciar sesion/i })).toBeInTheDocument()
    expect(calls.find((call) => call.url === '/api/v1/auth/password/reset')?.body).toEqual({
      token: 'abc123',
      newPassword: 'contrasena-larga-2',
    })
  })
})

describe('forgot-password 429 handling', () => {
  it('shows a rate-limit message with the requestId', async () => {
    installFetchStub({ 'POST /api/v1/auth/password/forgot': () => errorResponse(429, { code: 'RATE_LIMITED' }) })
    renderApp('/forgot-password')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/correo electronico/i), 'ana@ejemplo.com')
    await user.click(screen.getByRole('button', { name: /enviar enlace/i }))

    expect(await screen.findByText(/demasiadas solicitudes seguidas/i)).toBeInTheDocument()
    expect(screen.getByText(/requestId:/i)).toBeInTheDocument()
  })
})

describe('account password change', () => {
  it('sends the change request to the backend', async () => {
    const { calls } = installFetchStub({ 'POST /api/v1/auth/password/change': () => jsonResponse({ status: 204 }) })
    authenticate('VIEWER')
    renderApp('/account')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText(/contrasena actual/i), 'vieja-larga-1234')
    await user.type(screen.getByLabelText(/^nueva contrasena/i), 'nueva-larga-12345')
    await user.type(screen.getByLabelText(/repetir nueva contrasena/i), 'nueva-larga-12345')
    await user.click(screen.getByRole('button', { name: /actualizar contrasena/i }))

    expect(await screen.findByText(/contrasena actualizada correctamente/i)).toBeInTheDocument()
    expect(calls.find((call) => call.url === '/api/v1/auth/password/change')?.body).toEqual({
      currentPassword: 'vieja-larga-1234',
      newPassword: 'nueva-larga-12345',
    })
  })

  it('blocks a mismatched confirmation locally', async () => {
    const calls: string[] = []
    installFetchStub({
      'POST /api/v1/auth/password/change': () => {
        calls.push('called')
        return jsonResponse({ status: 204 })
      },
    })
    authenticate('VIEWER')
    renderApp('/account')
    const user = userEvent.setup()

    await user.type(await screen.findByLabelText(/contrasena actual/i), 'vieja-larga-1234')
    await user.type(screen.getByLabelText(/^nueva contrasena/i), 'nueva-larga-12345')
    await user.type(screen.getByLabelText(/repetir nueva contrasena/i), 'otra-cosa-distinta')
    await user.click(screen.getByRole('button', { name: /actualizar contrasena/i }))

    expect(await screen.findByText(/las contrasenas no coinciden/i)).toBeInTheDocument()
    expect(calls).toHaveLength(0)
  })
})

describe('session store invariants', () => {
  it('never persists the access token in web storage', async () => {
    installFetchStub({
      'POST /api/v1/auth/login': () => jsonResponse({ json: makeTokenResponse() }),
    })
    renderApp('/login')
    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/correo electronico/i), 'a@b.com')
    await user.type(screen.getByLabelText(/contrasena/i), 'contrasena-larga-1')
    await user.click(screen.getByRole('button', { name: /^entrar$/i }))

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))
    const accessToken = useAuthStore.getState().accessToken
    expect(accessToken).toBeTruthy()
    const dump = JSON.stringify({ ...sessionStorage, ...localStorage })
    expect(dump).not.toContain(accessToken as string)
  })

  it('keeps the Google entry point free of any client secret', () => {
    installFetchStub({})
    renderApp('/login')
    const link = screen.getByTestId('google-oauth-button')
    expect(link).toHaveAttribute('href')
    const href = link.getAttribute('href') ?? ''
    expect(href).not.toMatch(/client_secret|client_id|refresh_token|api_key/i)
  })
})
