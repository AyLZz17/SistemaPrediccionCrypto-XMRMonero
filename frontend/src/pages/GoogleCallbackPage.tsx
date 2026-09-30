import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/errors'
import { useAuthStore } from '../store/authStore'
import { Button, Panel, SkeletonPanel, StatusDot } from '../components/ui'

/**
 * `/auth/callback` — landing route of the backend-driven Google OAuth flow.
 *
 * Flow: the browser goes to the backend `/auth/google/authorize`, Google redirects
 * the browser to the backend `/auth/google/callback`, and the backend validates
 * state + nonce and responds 302 back here with the session in the URL
 * **fragment** (`#access_token=...`). The fragment is never sent to a server, never
 * lands in access logs or a `Referer` header, and is erased immediately below.
 *
 * The client secret only ever exists in the backend; nothing about the exchange
 * is duplicated here.
 */
export default function GoogleCallbackPage() {
  const navigate = useNavigate()
  const applyOAuthSession = useAuthStore((state) => state.applyOAuthSession)
  const [error, setError] = useState<ApiError | null>(null)
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

    try {
      applyOAuthSession({
        accessToken,
        refreshToken,
        expiresIn: Number(params.get('expires_in') ?? 900),
        role,
      })
      navigate('/dashboard', { replace: true })
    } catch (caught) {
      setError(caught instanceof ApiError ? caught : new ApiError({ kind: 'network' }))
    }
  }, [applyOAuthSession, navigate])

  if (error) {
    return (
      <div className="mx-auto w-full max-w-md space-y-5">
        <Panel className="border-accent-red/40">
          <div className="flex items-center gap-3">
            <StatusDot tone="danger" label="Fallo de autenticacion" />
          </div>
          <h1 className="mt-3 text-xl font-semibold text-ink">No pudimos iniciar sesion con Google</h1>
          <p className="mt-2 text-sm text-ink-secondary">{error.friendlyMessage}</p>
          {error.requestId ? (
            <p className="mt-3 font-mono text-xs text-ink-muted">requestId: {error.requestId}</p>
          ) : null}
          <div className="mt-5 flex flex-wrap gap-2">
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
    <div className="mx-auto w-full max-w-md space-y-5" role="status" aria-busy="true">
      <div className="text-center">
        <h1 className="text-2xl font-semibold text-ink">Completando inicio de sesion</h1>
        <p className="mt-1 text-sm text-ink-secondary">
          Estamos validando la respuesta de Google con el backend. No cierres esta ventana.
        </p>
      </div>
      <div className="flex justify-center">
        <StatusDot tone="active" label="Estableciendo la sesion" pulse />
      </div>
      <SkeletonPanel>
        <div className="space-y-2">
          <div className="skeleton-bar h-3 w-1/3" />
          <div className="skeleton-bar h-3 w-2/3" />
          <div className="skeleton-bar h-3 w-1/2" />
        </div>
      </SkeletonPanel>
    </div>
  )
}