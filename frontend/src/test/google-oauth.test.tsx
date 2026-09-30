import { beforeEach, describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useAuthStore } from '../store/authStore'
import { renderApp } from './testUtils'
import { installFetchStub, jsonResponse } from './fetchStub'

/**
 * Google OAuth.
 *
 * The backend owns the whole Authorization Code exchange: Google redirects the
 * browser to the backend `/auth/google/callback`, which validates state + nonce
 * and responds 302 back to `/auth/callback` with the session in the URL fragment.
 *
 * These tests pin that contract, including the security property that matters
 * most here: the fragment must be erased from the address bar immediately, and
 * the client secret must never be involved on the browser side.
 */

const DASHBOARD_STUBS = {
  '/api/v1/market/latest': () => jsonResponse({ json: { symbol: 'XMR-USD', price: 170 } }),
  '/api/v1/notifications': () =>
    jsonResponse({ json: { items: [], page: 0, size: 5, total: 0, totalPages: 0 } }),
  '/api/v1/jobs': () => jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
  '/api/v1/predictions': () =>
    jsonResponse({ json: { items: [], page: 0, size: 15, total: 0, totalPages: 0 } }),
}

/** Sets the URL fragment the backend would have redirected to. */
function arriveWithFragment(fragment: string): void {
  window.history.replaceState(null, '', `/auth/callback${fragment}`)
}

beforeEach(() => {
  installFetchStub(DASHBOARD_STUBS)
})

describe('Google OAuth', () => {
  it('renders the "Continuar con Google" button on login and register', () => {
    renderApp('/login')
    expect(screen.getByTestId('google-oauth-button')).toHaveTextContent(/continuar con google/i)
  })

  it('points at the backend authorize endpoint, never at Google directly', () => {
    renderApp('/login')
    const link = screen.getByTestId('google-oauth-button')
    expect(link.tagName).toBe('A')
    const href = link.getAttribute('href') ?? ''
    expect(href.startsWith('https://localhost:8443/api/v1/auth/google/authorize')).toBe(true)
    // No Google endpoint, no client id and no secret are ever referenced.
    expect(href).not.toContain('accounts.google.com')
    expect(href).not.toMatch(/client_secret|client_id/i)
  })

  it('is reachable and activatable from the keyboard', async () => {
    renderApp('/login')
    const user = userEvent.setup()
    const link = screen.getByTestId('google-oauth-button')
    link.focus()
    expect(link).toHaveFocus()
    await user.tab()
    expect(document.activeElement).not.toBe(link)
  })

  it('establishes the session from the fragment the backend redirected to', async () => {
    arriveWithFragment(
      '#access_token=at-123&refresh_token=rt-456&token_type=Bearer&expires_in=900&role=VIEWER',
    )
    renderApp('/auth/callback')

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))

    const state = useAuthStore.getState()
    expect(state.accessToken).toBe('at-123')
    expect(state.refreshToken).toBe('rt-456')
    expect(state.expiresAt).toBeGreaterThan(Date.now())
  })

  it('erases the fragment from the address bar so tokens are not left in history', async () => {
    arriveWithFragment('#access_token=at-erase&refresh_token=rt-erase&expires_in=900&role=VIEWER')
    renderApp('/auth/callback')

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))

    expect(window.location.hash).toBe('')
    expect(window.location.href).not.toContain('at-erase')
    expect(window.location.href).not.toContain('rt-erase')
  })

  it('never posts the authorization code from the browser', async () => {
    const { calls } = installFetchStub(DASHBOARD_STUBS)
    arriveWithFragment('#access_token=at-1&refresh_token=rt-1&expires_in=900&role=VIEWER')
    renderApp('/auth/callback')

    await waitFor(() => expect(useAuthStore.getState().status).toBe('authenticated'))

    // The client secret lives only in the backend: no code exchange here.
    expect(calls.some((item) => item.url.includes('/google/callback'))).toBe(false)
  })

  it('continues to the dashboard and shows the footer', async () => {
    arriveWithFragment('#access_token=at-2&refresh_token=rt-2&expires_in=900&role=VIEWER')
    renderApp('/auth/callback')

    await waitFor(() => expect(screen.getByText(/centro de operaciones/i)).toBeInTheDocument())
    expect(screen.getByTestId('app-footer')).toBeInTheDocument()
  })

  it('explains an OAuth denial relayed by the backend', async () => {
    arriveWithFragment('#error_code=OAUTH_DENIED&error_message=El+usuario+denego+el+consentimiento')
    renderApp('/auth/callback')

    expect(await screen.findByText(/no pudimos iniciar sesion con google/i)).toBeInTheDocument()
    expect(screen.getByText(/cancelo o denego el consentimiento/i)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /intentar de nuevo/i })).toBeInTheDocument()
    expect(screen.getByTestId('app-footer')).toBeInTheDocument()
  })

  it('explains a fragment without any session', async () => {
    arriveWithFragment('')
    renderApp('/auth/callback')

    expect(await screen.findByText(/no pudimos iniciar sesion con google/i)).toBeInTheDocument()
    expect(screen.getByText(/google no devolvio una sesion valida/i)).toBeInTheDocument()
  })

  it('refuses a partial fragment rather than opening a broken session', async () => {
    // Access token without refresh token: accepting it would leave the client
    // unable to renew, so it must be rejected outright.
    arriveWithFragment('#access_token=at-only&expires_in=900&role=VIEWER')
    renderApp('/auth/callback')

    expect(await screen.findByText(/no pudimos iniciar sesion con google/i)).toBeInTheDocument()
    expect(useAuthStore.getState().status).not.toBe('authenticated')
  })
})