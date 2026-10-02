import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { googleAuthorizeUrl, me, type GoogleConsentParams } from '../api/auth'
import { ApiError, toApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { Button, CheckboxField, Panel, SkeletonPanel, StatusDot } from '../components/ui'

/** authorize URL, or `#` if the env is broken (never a blank dead end). */
function safeAuthorizeUrl(consent: GoogleConsentParams): string {
  try {
    return googleAuthorizeUrl('/dashboard', consent)
  } catch {
    return '#configuracion-invalida'
  }
}

/**
 * `/auth/callback` — landing route of the backend-driven Google OAuth flow.
 *
 * Flow: the browser goes to the backend `/auth/google/authorize`, Google redirects
 * the browser to the backend `/auth/google/callback`, and the backend validates
 * state + nonce and responds 302 back here with the session in the URL
 * **fragment** (`#access_token=...`). The fragment is never sent to a server, never
 * lands in access logs or a `Referer` header, and is erased immediately below.
 *
 * The fragment carries tokens, not a profile. The profile is therefore fetched
 * from `GET /auth/me` before navigating: without that call the store would hold a
 * session with `user === null`, every `RoleRoute` would deny the user, and an
 * ADMIN who just signed in with Google would be treated as a VIEWER.
 *
 * The client secret only ever exists in the backend; nothing about the exchange
 * is duplicated here.
 */
export default function GoogleCallbackPage() {
  const navigate = useNavigate()
  const applyOAuthSession = useAuthStore((state) => state.applyOAuthSession)
  const setUser = useAuthStore((state) => state.setUser)
  const clearSession = useAuthStore((state) => state.clearSession)
  const [error, setError] = useState<ApiError | null>(null)
  /**
   * The backend refused to CREATE a new Google account because the one-time
   * `state` carried no acceptance of the terms and the data policy. Signing in
   * an existing account never reaches this state, so the only user who lands
   * here is someone whose account does not exist yet — exactly the moment when
   * the documents must be presented (Ley 1581 art. 8: prior, express consent).
   */
  const [needsConsent, setNeedsConsent] = useState(false)
  const [acceptTerms, setAcceptTerms] = useState(false)
  const [acceptDataPolicy, setAcceptDataPolicy] = useState(false)
  const [acceptMarketing, setAcceptMarketing] = useState(false)
  const handled = useRef(false)

  useEffect(() => {
    // Guardas contra el doble montaje de React 18 en modo estricto.
    if (handled.current) return
    handled.current = true

    const fragment = window.location.hash.startsWith('#')
      ? window.location.hash.slice(1)
      : window.location.hash

    // Borrado inmediato: los tokens no deben quedar en el historial ni visibles.
    window.history.replaceState(null, '', window.location.pathname + window.location.search)

    const params = new URLSearchParams(fragment)

    const errorCode = params.get('error_code')
    if (errorCode) {
      if (errorCode === 'CONSENT_REQUIRED') {
        setNeedsConsent(true)
        return
      }
      setError(
        new ApiError({
          kind: 'http',
          status: 401,
          code: errorCode,
          message: params.get('error_message') ?? undefined,
          friendlyMessage:
            errorCode === 'OAUTH_DENIED'
              ? 'Cancelo o denego el consentimiento de Google.'
              : 'No se pudo completar el inicio de sesion con Google.',
        }),
      )
      return
    }

    const accessToken = params.get('access_token')
    const refreshToken = params.get('refresh_token')
    if (!accessToken || !refreshToken) {
      setError(
        new ApiError({
          kind: 'http',
          status: 400,
          code: 'OAUTH_NO_SESSION',
          friendlyMessage:
            'Google no devolvio una sesion valida. Vuelva a iniciar el inicio de sesion con Google.',
        }),
      )
      return
    }

    const rawRole = params.get('role')
    const role =
      rawRole === 'ADMIN' || rawRole === 'ANALYST' || rawRole === 'VIEWER' ? rawRole : 'VIEWER'

    const establishSession = async () => {
      try {
        applyOAuthSession({
          accessToken,
          refreshToken,
          expiresIn: Number(params.get('expires_in') ?? 900),
          role,
        })
        // El fragmento no trae perfil: se pide al backend, que es la unica
        // fuente de verdad sobre el rol efectivo.
        setUser(await me())
        navigate('/dashboard', { replace: true })
      } catch (caught) {
        // Una sesion con el token valido pero sin perfil legible no es usable:
        // se cierra en vez de dejar al usuario en un estado a medias.
        clearSession('oauth_profile_unavailable')
        setError(toApiError(caught))
      }
    }

    void establishSession()
  }, [applyOAuthSession, clearSession, navigate, setUser])

  if (needsConsent) {
    // Nada se marca solo: los dos aceptes se vuelven a pedir aqui porque el
    // backend no tiene constancia de ellos para esta cuenta nueva.
    const authorizeHref = safeAuthorizeUrl({ acceptTerms, acceptDataPolicy, acceptMarketing })
    return (
      <div className="mx-auto w-full max-w-md">
        <Panel tone="strong">
          <div className="mb-4 border-b-2 border-hairline-strong pb-3">
            <p className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">Alta de cuenta con Google</p>
            <h1 className="mt-1 text-xl font-semibold tracking-tight text-ink">
              Acepta los documentos para crear tu cuenta
            </h1>
          </div>
          <p className="text-sm leading-normal text-ink-secondary">
            Google ha confirmado tu identidad, pero todavia no existe una cuenta en el sistema.
            Para crearla necesitamos tu aceptacion expresa de los siguientes documentos.
          </p>

          <div className="mt-4 space-y-3 border-t border-hairline-subtle pt-4" data-testid="google-consent">
            <CheckboxField
              data-testid="google-accept-terms"
              name="acceptTerms"
              required
              checked={acceptTerms}
              onChange={(event) => setAcceptTerms(event.target.checked)}
              label={
                <>
                  He leido y acepto los{' '}
                  <Link to="/terms" className="link-accent">
                    terminos y condiciones
                  </Link>
                  .
                </>
              }
            />
            <CheckboxField
              data-testid="google-accept-data-policy"
              name="acceptDataPolicy"
              required
              checked={acceptDataPolicy}
              onChange={(event) => setAcceptDataPolicy(event.target.checked)}
              label={
                <>
                  Acepto la{' '}
                  <Link to="/data-policy" className="link-accent">
                    politica de tratamiento de datos personales
                  </Link>
                  .
                </>
              }
            />
            <CheckboxField
              data-testid="google-accept-marketing"
              name="acceptMarketing"
              checked={acceptMarketing}
              onChange={(event) => setAcceptMarketing(event.target.checked)}
              label="Quiero recibir novedades y comunicaciones comerciales (opcional)."
            />
          </div>

          <div className="mt-5 flex flex-wrap gap-2 border-t-2 border-hairline-strong pt-4">
            {/* Navegacion completa: el endpoint de autorizacion responde 307 y
                el navegador debe salir hacia Google con state y consentimiento. */}
            <Button
              data-testid="google-consent-retry"
              disabled={!acceptTerms || !acceptDataPolicy}
              onClick={() => window.location.assign(authorizeHref)}
            >
              Aceptar y continuar
            </Button>
            <Link to="/register">
              <Button variant="secondary">Registrarme con correo</Button>
            </Link>
          </div>
        </Panel>
      </div>
    )
  }

  if (error) {
    return (
      <div className="mx-auto w-full max-w-md">
        <Panel tone="danger">
          <div className="mb-3 flex items-center justify-between gap-3 border-b-2 border-hairline-strong pb-3">
            <p className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">XMR-Forecast · OAuth</p>
            <StatusDot tone="danger" label="Fallo de autenticacion" />
          </div>
          <h1 className="text-xl font-semibold tracking-tight text-ink">No pudimos iniciar sesion con Google</h1>
          <p className="mt-2 text-sm leading-normal text-ink-secondary">{error.friendlyMessage}</p>
          {error.requestId ? (
            <p className="mt-3 font-mono text-xs tabular-nums text-ink-muted">requestId: {error.requestId}</p>
          ) : null}
          <div className="mt-5 flex flex-wrap gap-2 border-t border-hairline-subtle pt-4">
            <Link to="/login">
              <Button>Intentar de nuevo</Button>
            </Link>
            <Link to="/register">
              <Button variant="secondary">Crear cuenta con correo</Button>
            </Link>
          </div>
        </Panel>
      </div>
    )
  }

  return (
    <div className="mx-auto w-full max-w-md" role="status" aria-busy="true">
      <Panel tone="strong">
        <div className="mb-4 border-b-2 border-hairline-strong pb-3 text-center">
          <h1 className="text-xl font-semibold tracking-tight text-ink">Completando inicio de sesion</h1>
          <p className="mt-1 text-[13px] text-ink-secondary">
            Estamos validando la respuesta de Google con el backend. No cierres esta ventana.
          </p>
        </div>
        <div className="flex justify-center">
          <StatusDot tone="active" label="Estableciendo la sesion" pulse />
        </div>
        <div className="mt-4">
          <SkeletonPanel>
            <div className="space-y-2">
              <div className="skeleton-bar h-3 w-1/3" />
              <div className="skeleton-bar h-3 w-2/3" />
              <div className="skeleton-bar h-3 w-1/2" />
            </div>
          </SkeletonPanel>
        </div>
      </Panel>
    </div>
  )
}
