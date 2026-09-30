import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { Footer } from './Footer'
import { BrandMark } from './BrandMark'
import { StatusDot } from '../ui'

/**
 * Shell for every public route (landing, login, register, password flows and
 * the Google OAuth callback). Renders the mandatory `<Footer />` too, so the
 * public surface cannot lose it.
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

  return (
    <div className="flex min-h-screen flex-col bg-deep">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:z-50 focus:rounded-md focus:border focus:border-accent-cyan/60 focus:bg-surface-2 focus:px-4 focus:py-2 focus:text-sm focus:text-ink"
      >
        Saltar al contenido principal
      </a>

      <header
        aria-label="Cabecera publica"
        className="border-b border-hairline-subtle bg-surface-1/70 backdrop-blur-glass"
      >
        <div className="mx-auto flex h-header w-full max-w-content items-center gap-3 px-4 sm:px-6">
          <Link to="/" className="flex items-center gap-2.5" aria-label="XMR-Forecast, inicio">
            <BrandMark size={30} />
            <span className="text-sm font-semibold text-ink">XMR-Forecast</span>
          </Link>
          <span className="label-caps hidden sm:inline">Capacidad predictiva evaluada</span>

          <nav aria-label="Navegacion publica" className="ml-auto flex items-center gap-2">
            {!isAuth ? (
              <>
                <Link
                  to="/login"
                  className="rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
                >
                  Iniciar sesion
                </Link>
                <Link
                  to="/register"
                  className="rounded-md border border-accent-cyan/50 bg-accent-cyan-soft px-3 py-2 text-xs font-medium text-accent-cyan shadow-glow-cyan transition-colors duration-fast hover:bg-accent-cyan/20"
                >
                  Crear cuenta
                </Link>
              </>
            ) : (
              <Link
                to="/"
                className="rounded-md border border-hairline px-3 py-2 text-xs font-medium text-ink-secondary transition-colors duration-fast hover:border-hairline-strong hover:text-ink"
              >
                Volver al inicio
              </Link>
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
          <span className="label-caps">XMR-USD · datos historicos</span>
          <span className="label-caps">No es asesoria financiera</span>
        </div>
      </div>

      <Footer />
    </div>
  )
}
