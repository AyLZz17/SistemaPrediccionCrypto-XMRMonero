import { describe, expect, it } from 'vitest'
import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { renderApp } from './testUtils'
import { installFetchStub, jsonResponse } from './fetchStub'
import { LEGAL_CONTACT_EMAIL, LEGAL_DOCUMENTS, LEGAL_VERSION } from '../config/legal'
import { FOOTER_TEXT } from '../components/layout/Footer'

/**
 * Legal surface of the product:
 *   - the footer links to every published document, contact and the data
 *     deletion request, on every route;
 *   - each document renders the SAME version the backend records on consent;
 *   - registration presents the two mandatory consents, none pre-ticked;
 *   - the Google callback asks for consent before creating an account;
 *   - a broken/expired verification link offers the resend form.
 *
 * Everything here is contractual: dropping a link, pre-ticking a box or
 * losing the version label would go unnoticed in the UI but would break the
 * proof of acceptance on the server.
 */

const FOOTER_ROUTES = ['/', '/login', '/terms', '/legal-notice']

function stubApi(extra: Record<string, () => Response> = {}) {
  return installFetchStub({
    ...extra,
  })
}

describe('legal navigation in the footer', () => {
  it.each(FOOTER_ROUTES)('exposes every legal link on %s', (route) => {
    stubApi()
    renderApp(route)

    const nav = screen.getByTestId('footer-legal-nav')
    for (const document of LEGAL_DOCUMENTS) {
      const link = screen.getByTestId(`footer-link-${document.key}`)
      expect(nav).toContain(link)
      expect(link).toHaveAttribute('href', document.path)
      expect(link).toHaveTextContent(document.label)
    }

    expect(screen.getByTestId('footer-contact-link')).toHaveAttribute(
      'href',
      `mailto:${LEGAL_CONTACT_EMAIL}`,
    )
    const request = screen.getByTestId('footer-data-request-link')
    expect(request.getAttribute('href')).toContain('mailto:')
    expect(request.getAttribute('href')).toContain('subject=')
    expect(screen.getByTestId('app-footer-text')).toHaveTextContent(FOOTER_TEXT)
  })

  it('never pre-selects or hides the consents on the register form', async () => {
    stubApi()
    renderApp('/register')

    expect(screen.getByTestId('accept-terms')).not.toBeChecked()
    expect(screen.getByTestId('accept-data-policy')).not.toBeChecked()
    expect(screen.getByTestId('accept-marketing')).not.toBeChecked()

    // The documents are reachable from the very screen that asks for them.
    expect(screen.getByTestId('terms-link')).toHaveAttribute('href', '/terms')
    expect(screen.getByTestId('data-policy-link')).toHaveAttribute('href', '/data-policy')
  })
})

describe('legal documents', () => {
  const ROUTES: Array<[string, string]> = [
    ['/terms', 'Terminos y condiciones'],
    ['/privacy', 'Politica de privacidad'],
    ['/data-policy', 'Politica de tratamiento de datos personales'],
    ['/cookies', 'Politica de cookies'],
    ['/legal-notice', 'Aviso legal y contacto'],
  ]

  it.each(ROUTES)('renders %s with the published version', async (route, heading) => {
    stubApi()
    renderApp(route)

    expect(
      await screen.findByRole('heading', { level: 1, name: new RegExp(heading, 'i') }),
    ).toBeInTheDocument()

    // Same literal the backend stores in consent_records (R-43, checked on the
    // server side by LegalVersionsContractTest).
    expect(screen.getByTestId('legal-version')).toHaveTextContent(LEGAL_VERSION)

    // The scope disclaimer: never presented as legal advice.
    expect(screen.getByText(/no constituye asesoria juridica/i)).toBeInTheDocument()
  })

  it('offers the data deletion request with a prefilled subject', async () => {
    stubApi()
    renderApp('/legal-notice')

    const link = await screen.findByTestId('data-request-link')
    expect(link.getAttribute('href')).toContain(`mailto:${LEGAL_CONTACT_EMAIL}`)
    expect(decodeURIComponent(link.getAttribute('href') ?? '')).toContain(
      'Derechos de titular de datos',
    )
  })
})

describe('Google consent gate', () => {
  it('asks for both consents before creating an account, none pre-ticked', async () => {
    stubApi()
    window.history.replaceState(null, '', '/auth/callback#error_code=CONSENT_REQUIRED')
    renderApp('/auth/callback')

    const panel = await screen.findByTestId('google-consent')
    expect(panel).toBeInTheDocument()

    const terms = screen.getByTestId('google-accept-terms')
    const policy = screen.getByTestId('google-accept-data-policy')
    const marketing = screen.getByTestId('google-accept-marketing')
    expect(terms).not.toBeChecked()
    expect(policy).not.toBeChecked()
    expect(marketing).not.toBeChecked()

    const retry = screen.getByTestId('google-consent-retry') as HTMLButtonElement
    expect(retry).toBeDisabled()

    await userEvent.setup().click(terms)
    expect(retry).toBeDisabled()

    await userEvent.setup().click(policy)
    await waitFor(() => expect(retry).toBeEnabled())

    // No session may be established from an error fragment.
    expect(window.location.hash).toBe('')
  })
})

describe('email verification recovery', () => {
  it('offers the resend form when the link carries no token', async () => {
    const { calls } = stubApi({
      'POST /api/v1/auth/verify-email/resend': () => jsonResponse({ status: 204 }),
    })
    renderApp('/verify-email')

    expect(await screen.findByTestId('resend-block')).toBeInTheDocument()
    expect(screen.getByTestId('verify-error')).toHaveTextContent(
      /enlace de confirmacion no es valido/i,
    )

    const user = userEvent.setup()
    await user.type(screen.getByLabelText(/correo electronico/i), 'nuevo@ejemplo.com')
    await user.click(screen.getByRole('button', { name: /reenviar correo de verificacion/i }))

    await waitFor(() =>
      expect(
        screen.getByText(/hemos enviado un enlace nuevo/i),
      ).toBeInTheDocument(),
    )
    const resend = calls.find(
      (call) => call.url === '/api/v1/auth/verify-email/resend',
    )
    expect(resend?.method).toBe('POST')
    expect(resend?.body).toEqual({ email: 'nuevo@ejemplo.com' })
  })

  it('shows the resend form when the token is rejected', async () => {
    stubApi({
      'POST /api/v1/auth/verify-email': () =>
        new Response(
          JSON.stringify({
            timestamp: new Date().toISOString(),
            status: 400,
            error: 'Bad Request',
            code: 'TOKEN_EXPIRED',
            message: 'El enlace de verificacion ha caducado.',
            path: '/api/v1/auth/verify-email',
            requestId: 'req-1',
          }),
          { status: 400, headers: { 'content-type': 'application/json' } },
        ),
    })
    window.history.replaceState(null, '', '/verify-email?token=token-caducado')
    renderApp('/verify-email')

    expect(await screen.findByTestId('resend-block')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /no pudimos confirmar el correo/i })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /reenviar correo de verificacion/i })).toBeInTheDocument()
  })
})
