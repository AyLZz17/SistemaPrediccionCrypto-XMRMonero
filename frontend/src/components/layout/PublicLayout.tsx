import type { ReactNode } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Footer } from './Footer'
import { BrandMark } from './BrandMark'
import { StatusDot } from '../ui'
import { GoogleButton } from '../auth/GoogleButton'
import { useAuthStore } from '../../store/authStore'

/**
 * Shell for every public route (public dashboard, landing, login, register,
 * password flows, legal documents and the Google OAuth callback). Renders the
 * mandatory `<Footer />` too, so the public surface cannot lose it.
 *
 * The corner is REACTIVE (T-043): anonymous visitors get "Iniciar sesión" /
 * "Registrarse" / "Iniciar sesión con Google"; once the session exists the same
 * spot shows who is signed in, their role, the links to the account settings
 * and the authenticated dashboard, and the logout action. The switch happens in
 * this component, so it is visible on every public route, not only on the first
 * screen.
 */
export function PublicLayout({
  children,
  /** Compact variant for the auth pages. */
  variant = 'default',
}: {
  children: ReactNode
  variant?: 'default' | 'auth'
}) {
  const isAuth = variant === 'auth'
  const navigate = useNavigate()
  const status = useAuthStore((state) => state.status)
  const user = useAuthStore((state) => state.user)
  const clearSession = useAuthStore((state) => state.clearSession)
  const authenticated = status === 'authenticated' && user !== null

  return (
    <div className="flex min-h-screen flex-col bg-deep">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:border focus:border-accent-cyan/60 focus:bg-surface-2 focus:px-4 focus:py-2 focus:text-sm focus:text-ink"
      >
        Saltar al contenido principal
      </a>

      <header
        aria-label="Cabecera pública"
        className="border-b border-hairline-subtle bg-surface-1/70 backdrop-blur-glass"
      >
        <div className="mx-auto flex min-h-header w-full max-w-content flex-wrap items-center gap-3 px-4 py-2 sm:px-6">
          <Link to="/" className="flex items-center gap-2.5" aria-label="XMR-Forecast, inicio">
            <BrandMark size={26} />
            <span className="text-sm font-semibold text-ink">XMR-Forecast</span>
          </Link>
          <span className="label-caps hidden sm:inline">Capacidad predictiva evaluada</span>

          <nav aria-label="Navegación pública" className="ml-auto flex flex-wrap items-center justify-end gap-2">
            {isAuth ? (
              <Link
                to="/"
                className="rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
              >
                Volver al inicio
              </Link>
            ) : authenticated ? (
              <>
                <span
                  data-testid="public-identity"
                  className="hidden max-w-[200px] flex-col items-end leading-tight sm:flex"
                >
                  <span className="truncate text-xs font-medium text-ink">
                    {user?.fullName || user?.email}
                  </span>
                  <span className="font-mono text-[10px] uppercase tracking-wide text-ink-muted">
                    {user?.role}
                  </span>
                </span>
                <Link
                  to="/account"
                  className="rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
                >
                  Configuración
                </Link>
                <Link
                  to="/dashboard"
                  className="rounded-md border border-hairline-default bg-elevated px-3 py-2 text-xs font-medium text-ink transition-colors duration-fast hover:border-hairline-strong hover:bg-surface-2"
                >
                  Dashboard
                </Link>
                <button
                  type="button"
                  onClick={() => {
                    clearSession(null)
                    navigate('/', { replace: true })
                  }}
                  className="rounded-md border border-hairline px-3 py-2 font-mono text-xs uppercase tracking-wide text-ink-secondary transition-colors duration-fast hover:border-accent-red/40 hover:text-accent-red"
                >
                  Cerrar sesión
                </button>
              </>
            ) : (
              <>
                <Link
                  to="/login"
                  className="rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
                >
                  Iniciar sesión
                </Link>
                <Link
                  to="/register"
                  className="rounded-md border border-hairline-default bg-elevated px-3 py-2 text-xs font-medium text-ink transition-colors duration-fast hover:border-hairline-strong hover:bg-surface-2"
                >
                  Registrarse
                </Link>
                <GoogleButton
                  variant="compact"
                  testId="google-header-oauth-button"
                  returnTo="/dashboard"
                />
              </>
            )}
          </nav>
        </div>
      </header>

      <main id="main-content" className="flex-1" tabIndex={-1}>
        <div className="mx-auto w-full max-w-content px-4 py-8 sm:px-6 lg:py-12">{children}</div>
      </main>

      <div className="border-t border-hairline-subtle bg-surface-inset/40">
        <div className="mx-auto flex w-full max-w-content flex-wrap items-center justify-center gap-x-6 gap-y-2 px-4 py-3 sm:px-6">
          <StatusDot tone="success" label="Canal cifrado" />
          <span className="label-caps">XMR-USD · datos históricos</span>
          <span className="label-caps">No es asesoría financiera</span>
        </div>
      </div>

      <Footer />
    </div>
  )
}